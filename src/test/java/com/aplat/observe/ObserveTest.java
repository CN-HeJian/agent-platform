package com.aplat.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.DefaultToolPolicy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U23 验收：事件流 → span 树 → 指标。
 *
 * <p>这个类里最该看的两条：**崩溃的会话也有一棵完整的树**（最后那个 span 是开口的，
 * 它就是"崩在哪一步"的答案），以及**配对靠 id 不靠顺序**（一个 step 里两次同名调用
 * 不会被配成一对）。
 */
class ObserveTest {

    private static Tool sink(String name) {
        return Tool.of(ToolSpec.of(name, "写个标记", "{}"), call -> ToolResult.ok("sunk"));
    }

    private Platform platform(ScriptedLlmAdapter llm, InMemoryStore store) {
        return Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), store, log -> Hitl.autoAllow(),
                reg -> {
                    reg.register(sink("sink_a"));
                    reg.register(sink("sink_b"));
                });
    }

    // ---------------------------------------------------------------- span 树

    @Test
    @DisplayName("一次完整会话：turn/step/tool 三层都在，且最内层是工具")
    void projectsACompleteTurn() {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("sink_a", "{}")
                .thenText("做完了");
        Platform p = platform(llm, store);
        p.durable().start(p.durable().submit("s1", "干点活").taskId());

        var trace = TraceBuilder.build(p.sessionLog(), "s1");
        var spans = trace.flatten();

        assertEquals(1, trace.byKind("turn").size());
        assertEquals(2, trace.byKind("step").size(), "两步：调工具的那步 + 收口那步");
        assertEquals(1, trace.byKind("tool").size());
        assertEquals(0, trace.openSpans(), "正常跑完的会话不该有开口的 span");

        var tool = trace.byKind("tool").get(0);
        assertEquals("sink_a", tool.attributes().get("tool"));
        assertTrue(tool.ended());
        assertTrue(spans.size() >= 4, "树的节点数不该少到只剩一层: " + spans.size());
        // 工具 span 必须挂在某一步之下，而不是平铺在根上
        assertTrue(trace.roots().get(0).children().stream()
                .anyMatch(s -> s.kind().equals("step") && !s.children().isEmpty()),
                "工具 span 应当挂在 step 里，否则时间线上看不出它属于哪一步");
    }

    @Test
    @DisplayName("崩掉的会话也有一棵树：最后一个 span 是开口的，它指出崩在哪")
    void crashedSessionStillHasATrace() {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("sink_a", "{}")
                .then("die", req -> {
                    throw new Error("process died");
                })
                .thenText("续跑才说的话");
        Platform p = platform(llm, store);
        String taskId = p.durable().submit("s1", "两步活").taskId();
        org.junit.jupiter.api.Assertions.assertThrows(Error.class, () -> p.durable().start(taskId));

        var trace = TraceBuilder.build(p.sessionLog(), "s1");
        assertTrue(trace.openSpans() >= 1, "崩溃的会话必须有开口 span");
        var open = trace.flatten().stream().filter(s -> !s.ended()).toList();
        assertTrue(open.stream().anyMatch(s -> s.kind().equals("step")),
                "开口的应当是那个没跑完的 step，实际：" + open);

        // 崩在工具调用与结果之间时，那个工具 span 也要开口——这是"崩在副作用中间"的直接证据
        var half = TraceBuilder.build(p.sessionLog(), "s1").flatten().stream()
                .filter(s -> s.kind().equals("tool") && !s.ended())
                .toList();
        assertTrue(half.isEmpty(), "这一例的工具其实返回了结果（崩在下一步），所以它应当是闭合的");
    }

    @Test
    @DisplayName("配对按 id 不按顺序：同一步里两次同名调用各自成对")
    void pairsByIdNotByOrder() {
        InMemoryStore store = new InMemoryStore();
        // 一次 assistant 消息里发两个同名调用——按顺序配对会在这里翻车
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .then("two-calls", req -> List.of(
                        new com.aplat.seam.LlmChunk.ToolCallDelta(
                                com.aplat.seam.ToolCall.of("sink_a", "{}")),
                        new com.aplat.seam.LlmChunk.ToolCallDelta(
                                new com.aplat.seam.ToolCall("call_second", "sink_a", "{\"x\":1}")),
                        new com.aplat.seam.LlmChunk.Done("tool_calls")))
                .thenText("好了");
        Platform p = platform(llm, store);
        p.durable().start(p.durable().submit("s1", "两次调用").taskId());

        var tools = TraceBuilder.build(p.sessionLog(), "s1").byKind("tool");
        assertEquals(2, tools.size());
        for (var t : tools) {
            assertTrue(t.ended(), "两个调用都该配上结果：" + t.name());
        }
        // 两个 span 的 callId 必须不同——如果实现按顺序配，这里会因为"取到了同一个"而露馅
        assertFalse(tools.get(0).attributes().get("callId")
                .equals(tools.get(1).attributes().get("callId")));
    }

    @Test
    @DisplayName("幂等回放在树里看得见，并且标明「未执行」")
    void replayShowsUpAsItsOwnSpan() {
        InMemoryStore store = new InMemoryStore();
        // 手工造一对"回放"事件：真实场景里它来自崩溃后重放同一步
        var log = new EventSourcedSessionLog(store);
        log.append("s1", SessionLog.EV_TURN_START, Map.of("input", "x"));
        log.append("s1", SessionLog.EV_STEP_START, Map.of("step", 1));
        log.append("s1", SessionLog.EV_TOOL_REPLAYED, Map.of(
                "step", 1, "id", "call_1", "tool", "shell", "reason", "result-unknown"));
        log.append("s1", SessionLog.EV_TURN_CLOSED, Map.of("step", 1, "reason", "completed"));

        var tool = TraceBuilder.build(log, "s1").byKind("tool").get(0);
        assertEquals(true, tool.attributes().get("replayed"));
        assertEquals("result-unknown", tool.attributes().get("reason"));
        assertTrue(tool.name().contains("回放"), "名字里就要看出它没被执行: " + tool.name());
    }

    @Test
    @DisplayName("人机等待的耗时是 span 的一部分（那 3 秒是人在点按钮，不是系统慢）")
    void hitlWaitIsVisible() throws Exception {
        InMemoryStore store = new InMemoryStore();
        var log = new EventSourcedSessionLog(store);
        log.append("s1", SessionLog.EV_TURN_START, Map.of("input", "x"));
        log.append("s1", SessionLog.EV_STEP_START, Map.of("step", 1));
        log.append("s1", SessionLog.EV_HITL_REQUEST,
                Map.of("requestId", "h1", "tool", "shell", "reason", "会执行命令", "timeoutSec", 60));
        Thread.sleep(60);
        log.append("s1", SessionLog.EV_HITL_RESOLVED,
                Map.of("requestId", "h1", "tool", "shell", "decision", "once"));
        log.append("s1", SessionLog.EV_TURN_CLOSED, Map.of("step", 1, "reason", "completed"));

        var wait = TraceBuilder.build(log, "s1").byKind("hitl").get(0);
        assertEquals("once", wait.attributes().get("decision"));
        assertTrue(wait.durationMillis() >= 50,
                "等待时长必须真的量出来，实际 " + wait.durationMillis() + "ms");
    }

    @Test
    @DisplayName("提前批准记成「无需确认」，与「有人批了」区分开")
    void preApprovalIsItsOwnSpan() {
        InMemoryStore store = new InMemoryStore();
        var log = new EventSourcedSessionLog(store);
        log.append("s1", SessionLog.EV_STEP_START, Map.of("step", 1));
        log.append("s1", SessionLog.EV_HITL_PREAPPROVED,
                Map.of("tool", "shell", "approvedBy", "sch-1", "args", "{\"command\":\"x\"}"));

        var span = TraceBuilder.build(log, "s1").byKind("hitl").get(0);
        assertEquals(true, span.attributes().get("autoApproved"));
        assertEquals("sch-1", span.attributes().get("approvedBy"));
    }

    // ------------------------------------------------------------------ 指标

    @Test
    @DisplayName("指标的计数与事件流一致，且重启后不清零（它本来就没存过）")
    void metricsAreDerivedNotCounted() {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("sink_a", "{}")
                .thenText("好了");
        Platform p = platform(llm, store);
        p.durable().start(p.durable().submit("s1", "干点活").taskId());

        var m = new Metrics(store, p.sessionLog());
        Map<String, Object> snap = m.snapshot();
        assertEquals(1, snap.get("aplat_sessions_total"));
        assertEquals(1, snap.get("aplat_tool_calls_total"));
        assertEquals(0, snap.get("aplat_tool_errors_total"));
        assertEquals(1, snap.get("aplat_tasks_total"));
        assertEquals(1, ((Map<?, ?>) snap.get("aplat_tool_calls_by_tool")).get("sink_a"));

        // 换一个新的 Metrics 实例（等价于进程重启后重新读）：
        // 数字必须一样——这是"不做进程内计数器"换来的性质
        var afterRestart = new Metrics(store, new EventSourcedSessionLog(store)).snapshot();
        assertEquals(snap.get("aplat_tool_calls_total"), afterRestart.get("aplat_tool_calls_total"));
        assertEquals(snap.get("aplat_events_total"), afterRestart.get("aplat_events_total"));
    }

    @Test
    @DisplayName("Prometheus 文本：带标签的行合法，且零值也要出（否则图上会断一截）")
    void prometheusFormat() {
        InMemoryStore store = new InMemoryStore();
        var m = new Metrics(store, new EventSourcedSessionLog(store));
        String text = m.prometheus();

        assertTrue(text.contains("aplat_sessions_total 0"), text);
        for (com.aplat.durable.TaskState state : com.aplat.durable.TaskState.values()) {
            assertTrue(text.contains("aplat_tasks_by_state_by_" + state.name().toLowerCase() + " "),
                    "缺少 " + state + " 的零值行");
        }

        // 标签值必须被净化：工具名里带连字符/点号会让整行被采集器丢弃
        InMemoryStore s2 = new InMemoryStore();
        var log2 = new EventSourcedSessionLog(s2);
        log2.append("s1", SessionLog.EV_TOOL_CALL, Map.of("id", "c1", "tool", "my.tool-x", "args", "{}"));
        String t2 = new Metrics(s2, log2).prometheus();
        assertTrue(t2.contains("aplat_tool_calls_by_my_tool_x 1"), t2);
        assertFalse(t2.contains("my.tool-x"), "标签值没被净化：" + t2);
    }

    @Test
    @DisplayName("JsonLog 的一行是可解析的 JSON（用一个读得回来的小解析器验）")
    void jsonLogLineIsParseable() throws Exception {
        // 借 Jackson 验证：这是"输出真的是 JSON"最直接的证据
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String captured = capture(() -> JsonLog.of("web").warn("有问题\"带引号\"\n下一行", Map.of("code", 7)));
        var node = mapper.readTree(captured);
        assertEquals("WARN", node.path("level").asText());
        assertEquals("web", node.path("logger").asText());
        assertEquals(7, node.path("code").asInt());
        assertTrue(node.path("msg").asText().contains("带引号"));
        assertEquals(1, captured.lines().count(), "必须恰好一行");
    }

    /** 把一次日志写入的 stdout 抓回来。 */
    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    @Test
    @DisplayName("指标里能看到幂等回放与续跑的累计次数——它们是排查时最先要看的两个数")
    void metricsExposeDurabilitySignals() {
        InMemoryStore store = new InMemoryStore();
        var log = new EventSourcedSessionLog(store);
        log.append("s1", SessionLog.EV_TURN_RESUMED,
                Map.of("fromStep", 2, "restoredMessages", 4, "taskId", "t1"));
        log.append("s1", SessionLog.EV_TOOL_REPLAYED,
                Map.of("step", 2, "id", "c1", "tool", "shell", "reason", "result-unknown"));

        Map<String, Object> snap = new Metrics(store, log).snapshot();
        assertEquals(1, snap.get("aplat_turns_resumed_total"));
        assertEquals(1, snap.get("aplat_tool_replayed_total"));
        assertTrue(new Metrics(store, log).eventTypes().contains(SessionLog.EV_TOOL_REPLAYED));
    }
}
