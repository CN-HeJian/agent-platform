package com.aplat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U08 验收：**前端可依赖的事件序列固定**——而这里钉的是"固定成 AG-UI 规范的样子"。
 *
 * <p>投影器是纯状态机，所以能脱离网络、脱离模型把它每个分支单独验掉。
 * 之后接入 CopilotKit 时若渲染不对，能立刻分清是"投影错"还是"前端错"。
 */
class AgUiProjectorTest {

    private AgUiProjector projector() {
        return new AgUiProjector("t-1", "r-1");
    }

    private static SessionEvent ev(long seq, String type, Map<String, Object> payload) {
        return new SessionEvent("t-1", seq, type, payload, Instant.EPOCH);
    }

    private static List<String> types(List<Map<String, Object>> events) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            out.add(String.valueOf(e.get("type")));
        }
        return out;
    }

    @Test
    @DisplayName("turn.started → RUN_STARTED 带 threadId/runId（客户端要靠它认领这次运行）")
    void turnStartBecomesRunStarted() {
        List<Map<String, Object>> out = projector().project(ev(2, "turn.started", Map.of("input", "hi")));

        assertEquals(List.of("RUN_STARTED"), types(out));
        assertEquals("t-1", out.get(0).get("threadId"));
        assertEquals("r-1", out.get(0).get("runId"));
    }

    @Test
    @DisplayName("文本 chunk：首条补 START，后续共用同一个 messageId，收口时补 END")
    void textChunksAreWrappedInOneMessage() {
        AgUiProjector p = projector();
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(p.project(ev(1, "turn.started", Map.of("input", "hi"))));
        out.addAll(p.project(ev(4, "step.started", Map.of("step", 1))));
        out.addAll(p.project(ev(5, "llm.chunk", Map.of("step", 1, "text", "你"))));
        out.addAll(p.project(ev(6, "llm.chunk", Map.of("step", 1, "text", "好"))));
        out.addAll(p.project(ev(7, "turn.closed", Map.of("reason", "completed"))));

        assertEquals(List.of(
                "RUN_STARTED",
                "STEP_STARTED",
                "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END",
                "STEP_FINISHED",
                "RUN_FINISHED"), types(out));

        String messageId = out.get(2).get("messageId").toString();
        assertEquals("assistant", out.get(2).get("role"));
        assertEquals("你", out.get(3).get("delta"));
        assertEquals("好", out.get(4).get("delta"));
        assertEquals(messageId, out.get(5).get("messageId"), "同一条消息的三个事件必须共用一个 messageId");
    }

    @Test
    @DisplayName("RUN_STARTED / RUN_FINISHED 必须配对：没有起点就不该凭空冒出终点")
    void runFinishedWithoutRunStartedIsSuppressed() {
        // 只给收口事件（真实流里不会出现，但投影器不该因此吐出无源的 RUN_FINISHED）
        List<Map<String, Object>> out = projector().project(ev(1, "turn.closed", Map.of("reason", "completed")));

        assertTrue(out.isEmpty(), "协议要求成对，孤儿 RUN_FINISHED 会让客户端状态机卡住: " + types(out));
    }

    @Test
    @DisplayName("进入下一个 step 会关掉上一条消息——否则前端把两段回答拼成一段")
    void nextStepClosesOpenMessage() {
        AgUiProjector p = projector();
        p.project(ev(1, "turn.started", Map.of()));
        p.project(ev(2, "step.started", Map.of("step", 1)));
        p.project(ev(3, "llm.chunk", Map.of("text", "第一段")));

        List<Map<String, Object>> out = p.project(ev(4, "step.started", Map.of("step", 2)));

        assertEquals(List.of("TEXT_MESSAGE_END", "STEP_FINISHED", "STEP_STARTED"), types(out));
    }

    @Test
    @DisplayName("STEP_STARTED / STEP_FINISHED 必须成对——不成对时客户端会拒收整条流")
    void stepLifecycleIsBalanced() {
        // 这条是为一个真实 bug 补的回归用例：
        // 前端（CopilotKit）报 "Cannot send 'RUN_FINISHED' while steps are still active: step-1, step-2"，
        // 因为之前只发 STEP_STARTED 不发 STEP_FINISHED。单测当时看不出来，真跑一次客户端才暴露。
        AgUiProjector p = projector();
        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(p.project(ev(1, "turn.started", Map.of())));
        all.addAll(p.project(ev(2, "step.started", Map.of("step", 1))));
        all.addAll(p.project(ev(3, "llm.chunk", Map.of("text", "第一段"))));
        all.addAll(p.project(ev(4, "step.started", Map.of("step", 2))));
        all.addAll(p.project(ev(5, "llm.chunk", Map.of("text", "第二段"))));
        all.addAll(p.project(ev(6, "turn.closed", Map.of("reason", "completed"))));

        List<String> started = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        for (Map<String, Object> e : all) {
            if ("STEP_STARTED".equals(e.get("type"))) {
                started.add(String.valueOf(e.get("stepName")));
            } else if ("STEP_FINISHED".equals(e.get("type"))) {
                finished.add(String.valueOf(e.get("stepName")));
            }
        }

        assertEquals(started, finished, "每个 stepName 都要有配对的 FINISHED");
        assertEquals(List.of("step-1", "step-2"), started);

        int lastFinish = types(all).lastIndexOf("STEP_FINISHED");
        int runFinished = types(all).indexOf("RUN_FINISHED");
        assertTrue(lastFinish < runFinished, "所有 step 必须先关完，才能发 RUN_FINISHED");
    }

    @Test
    @DisplayName("step 是串行的：开新 step 会关掉上一个，所以收尾只需关最后一个")
    void stepsAreSequentialNotNested() {
        AgUiProjector p = projector();
        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(p.project(ev(1, "turn.started", Map.of())));
        all.addAll(p.project(ev(2, "step.started", Map.of("step", 1))));
        all.addAll(p.project(ev(3, "step.started", Map.of("step", 2))));

        // 第二步开的时候第一步已经关了，不该攒着两个未关的 step
        assertEquals(List.of("RUN_STARTED", "STEP_STARTED", "STEP_FINISHED", "STEP_STARTED"), types(all));

        List<Map<String, Object>> out = p.project(ev(4, "turn.closed", Map.of("reason", "completed")));
        assertEquals(List.of("STEP_FINISHED", "RUN_FINISHED"), types(out));
        assertEquals("step-2", out.get(0).get("stepName"));
    }

    @Test
    @DisplayName("工具调用：START → ARGS →（结果时）END → RESULT，四件套配对完整")
    void toolCallIsPairWise() {
        AgUiProjector p = projector();
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(p.project(ev(3, "tool.call", Map.of(
                "id", "call_1", "tool", "shell", "args", "{\"command\":\"ls\"}"))));
        out.addAll(p.project(ev(4, "tool.result", Map.of(
                "id", "call_1", "tool", "shell", "ok", true, "content", "a.txt"))));

        assertEquals(List.of(
                "TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END", "TOOL_CALL_RESULT"), types(out));
        assertEquals("call_1", out.get(0).get("toolCallId"));
        assertEquals("shell", out.get(0).get("toolCallName"));
        assertEquals("{\"command\":\"ls\"}", out.get(1).get("delta"), "参数以整段 delta 下发");
        assertEquals("a.txt", out.get(3).get("content"));
        assertEquals("tool", out.get(3).get("role"));
    }

    @Test
    @DisplayName("工具失败时，前端看到的文本与模型看到的观察完全一致（含 ERROR[code] 前缀）")
    void failedToolResultMatchesModelObservation() {
        AgUiProjector p = projector();
        p.project(ev(1, "tool.call", Map.of("id", "c1", "tool", "shell", "args", "{}")));

        List<Map<String, Object>> out = p.project(ev(2, "tool.result", Map.of(
                "id", "c1", "tool", "shell", "ok", false,
                "errorCode", "BLOCKED_BY_POLICY", "content", "rm denied")));

        assertEquals("ERROR[BLOCKED_BY_POLICY] rm denied", out.get(1).get("content"));
    }

    @Test
    @DisplayName("内部记账事件不外泄——排查用的东西不该出现在聊天窗口里")
    void internalBookkeepingIsNotLeaked() {
        AgUiProjector p = projector();

        assertTrue(p.project(ev(1, "input.claimed", Map.of("text", "hi"))).isEmpty());
        assertTrue(p.project(ev(2, "context.prepared", Map.of("step", 1, "estimatedTokens", 40))).isEmpty());
        assertTrue(p.project(ev(3, "llm.chunk", Map.of("step", 1, "usage", "10/20"))).isEmpty(),
                "usage 记账 chunk 不是文本，不该被当成 token 推给前端");
    }

    @Test
    @DisplayName("state.snapshot → STATE_SNAPSHOT（前端据此更新共享状态）")
    void stateSnapshotIsForwarded() {
        List<Map<String, Object>> out = projector().project(ev(5, "state.snapshot",
                Map.of("step", 2, "messages", 4, "state", "{\"step\":2}")));

        assertEquals(List.of("STATE_SNAPSHOT"), types(out));
        assertTrue(out.get(0).get("snapshot") instanceof Map);
    }

    @Test
    @DisplayName("error → RUN_ERROR，且先关掉未收口的消息")
    void errorClosesMessageFirst() {
        AgUiProjector p = projector();
        p.project(ev(1, "turn.started", Map.of()));
        p.project(ev(2, "step.started", Map.of("step", 1)));
        p.project(ev(3, "llm.chunk", Map.of("text", "说到一半")));

        List<Map<String, Object>> out = p.project(ev(4, "error", Map.of("step", 1, "error", "upstream 503")));

        assertEquals(List.of("TEXT_MESSAGE_END", "STEP_FINISHED", "RUN_ERROR"), types(out));
        assertEquals("upstream 503", out.get(2).get("message"));
    }

    @Test
    @DisplayName("异常中断时 finish() 兜住 dangling 消息——否则前端光标一直闪")
    void finishClosesDanglingMessage() {
        AgUiProjector p = projector();
        p.project(ev(1, "turn.started", Map.of()));
        p.project(ev(2, "step.started", Map.of("step", 1)));
        p.project(ev(3, "llm.chunk", Map.of("text", "没说完就断了")));

        // 没有 turn.closed
        List<Map<String, Object>> out = p.finish();

        assertEquals(List.of("TEXT_MESSAGE_END", "STEP_FINISHED", "RUN_FINISHED"), types(out));
        assertTrue(p.finish().isEmpty(), "finish 必须幂等，不能重复补事件");
    }

    @Test
    @DisplayName("正常收口后 finish() 不再补事件（不能出现两个 RUN_FINISHED）")
    void finishAfterNormalCloseEmitsNothing() {
        AgUiProjector p = projector();
        p.project(ev(1, "turn.started", Map.of()));
        p.project(ev(2, "step.started", Map.of("step", 1)));
        p.project(ev(3, "llm.chunk", Map.of("text", "答完了")));
        p.project(ev(4, "turn.closed", Map.of("reason", "completed")));

        assertTrue(p.finish().isEmpty());
    }

    @Test
    @DisplayName("每条事件都带 type，且 type 在最前（人看 JSON 时第一眼就是它）")
    void everyEventHasTypeFirst() {
        AgUiProjector p = projector();
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(p.project(ev(1, "turn.started", Map.of())));
        out.addAll(p.project(ev(2, "step.started", Map.of("step", 1))));
        out.addAll(p.project(ev(3, "llm.chunk", Map.of("text", "x"))));

        for (Map<String, Object> e : out) {
            assertFalse(e.isEmpty());
            assertEquals("type", e.keySet().iterator().next(), "type 必须是第一个键");
            assertTrue(e.get("type") instanceof String);
        }
    }

    @Test
    @DisplayName("未知事件类型直接丢弃（不是崩，也不是硬塞成 CUSTOM 噪音）")
    void unknownEventIsDropped() {
        assertTrue(projector().project(ev(1, "some.future.event", Map.of("x", 1))).isEmpty());

        // 用可变 map 确认不会因为 payload 里塞了怪东西而炸
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("nested", Map.of("a", 1));
        assertTrue(projector().project(ev(2, "context.prepared", weird)).isEmpty());
    }
}
