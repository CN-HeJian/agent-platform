package com.aplat.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.SessionLog;
import com.aplat.seam.ToolResult;
import com.aplat.session.EventSourcedSessionLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Agent 循环的端到端验收。全部用脚本化模型跑，不依赖网络与真实 LLM——
 * 这样"循环逻辑对不对"和"模型答得好不好"是两件可以分开判断的事。
 */
class AgentLoopTest {

    private Platform platform(ScriptedLlmAdapter llm, Hitl hitl, LoopBudget budget) {
        return Platform.assemble(llm, new ProcessSandbox(), hitl, budget);
    }

    @Test
    @DisplayName("U04 验收：三步任务跑通——工具调用 → 观察 → 最终答复")
    void threeStepTaskCompletes() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("add", "{\"a\":20,\"b\":22}")
                .thenText("结果是 42。");

        Platform p = platform(llm, Hitl.autoAllow(), LoopBudget.defaults());
        TurnResult r = p.loop().run("s1", "算一下 20 + 22");

        assertTrue(r.ok());
        assertEquals(TurnResult.Status.COMPLETED, r.status());
        assertEquals(2, r.steps());
        assertEquals("结果是 42。", r.finalText());

        var log = (EventSourcedSessionLog) p.sessionLog();
        assertTrue(log.ofType("s1", SessionLog.EV_TOOL_CALL).size() == 1);
        assertTrue(log.ofType("s1", SessionLog.EV_TOOL_RESULT).get(0).payload().get("ok").equals(true));
        assertTrue(log.ofType("s1", SessionLog.EV_TURN_CLOSED).get(0).str("reason").equals("completed"));
    }

    @Test
    @DisplayName("U04 验收：模型编造工具名不崩，且能拿到错误码自纠")
    void hallucinatedToolSelfCorrects() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("delete_everything", "{}")
                .thenText("抱歉，我没有这个工具，改用 add 完成。");

        Platform p = platform(llm, Hitl.autoAllow(), LoopBudget.defaults());
        TurnResult r = p.loop().run("s1", "帮我删数据");

        assertTrue(r.ok(), "幻觉不应导致失败");
        // 第二步模型必须看到结构化错误
        String secondTurnObservations = llm.requests().get(1).messages().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(m -> m.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(secondTurnObservations.contains(ToolResult.ERR_UNKNOWN_TOOL));
    }

    @Test
    @DisplayName("预算保护：模型一直调工具时，步数用尽即收口，不会无限跑")
    void maxStepsTerminatesLoop() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("add", "{\"a\":1,\"b\":1}")
                .thenToolCall("add", "{\"a\":1,\"b\":1}")
                .thenToolCall("add", "{\"a\":1,\"b\":1}");

        Platform p = platform(llm, Hitl.autoAllow(), new LoopBudget(3, 8_000));
        TurnResult r = p.loop().run("s1", "无限循环测试");

        assertEquals(TurnResult.Status.MAX_STEPS, r.status());
        assertEquals(3, r.steps());
        assertTrue(((EventSourcedSessionLog) p.sessionLog())
                .ofType("s1", SessionLog.EV_TURN_CLOSED).get(0).str("reason")
                .equals("max_steps_exceeded"));
    }

    @Test
    @DisplayName("HITL 拒绝后循环不中断，而是把拒绝当观察回填给模型改道")
    void denialBecomesObservationAndLoopContinues() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"ls /tmp\"}")
                .thenText("好的，我换个不需要执行命令的方式回答。");

        Platform p = platform(llm, Hitl.autoDeny("用户不批准执行命令"), LoopBudget.defaults());
        TurnResult r = p.loop().run("s1", "看看 /tmp");

        assertTrue(r.ok());
        assertEquals(2, r.steps());
        var log = (EventSourcedSessionLog) p.sessionLog();
        assertEquals(ToolResult.ERR_DENIED,
                log.ofType("s1", SessionLog.EV_TOOL_RESULT).get(0).str("errorCode"));
    }

    @Test
    @DisplayName("U13 验收：拒绝回填后模型真的改道——换成另一个工具把任务做完")
    void denialMakesModelRerouteToAnotherTool() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"ls /tmp\"}")
                .thenToolCall("add", "{\"a\":20,\"b\":22}")
                .thenText("不让执行命令，那我直接算：结果是 42。");

        Platform p = platform(llm, Hitl.autoDeny("用户不批准执行命令"), LoopBudget.defaults());
        TurnResult r = p.loop().run("s1", "看看 /tmp，再帮我算 20+22");

        assertTrue(r.ok(), r.finalText());
        assertEquals(3, r.steps(), "一步被拒 → 一步改道 → 一步收口");

        var log = (EventSourcedSessionLog) p.sessionLog();
        assertEquals(ToolResult.ERR_DENIED, log.ofType("s1", SessionLog.EV_TOOL_RESULT).get(0).str("errorCode"));
        assertEquals(Boolean.TRUE, log.ofType("s1", SessionLog.EV_TOOL_RESULT).get(1).payload().get("ok"));

        // 验收的关键：模型在第三步必须**看得见**被拒这件事与理由，
        // 否则"改道"只是碰巧——下次它还会再撞一次同一道门。
        String seen = llm.requests().get(2).messages().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(m -> m.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(seen.contains(ToolResult.ERR_DENIED), "拒绝要以观察形式回填: " + seen);
        assertTrue(seen.contains("用户不批准执行命令"), "理由也要带上，模型才知道边界在哪");
    }

    @Test
    @DisplayName("模型异常被记为 error 事件，turn 以 ERROR 收口而不是抛给调用方")
    void llmFailureIsContained() {
        var brokenLlm = new com.aplat.seam.LlmAdapter() {
            @Override
            public String id() {
                return "llm.broken";
            }

            @Override
            public void stream(com.aplat.seam.LlmRequest request,
                               java.util.function.Consumer<com.aplat.seam.LlmChunk> sink) {
                throw new IllegalStateException("upstream 503");
            }
        };

        Platform broken = Platform.assemble(brokenLlm, new ProcessSandbox(), Hitl.autoAllow(), LoopBudget.defaults());
        TurnResult r = broken.loop().run("s1", "any");

        assertEquals(TurnResult.Status.ERROR, r.status());
        assertEquals(1, ((EventSourcedSessionLog) broken.sessionLog())
                .ofType("s1", SessionLog.EV_ERROR).size());

        // 正常装配路径依然可用，证明坏掉的是模型而不是内核
        ScriptedLlmAdapter ok = new ScriptedLlmAdapter().thenText("fine");
        Platform healthy = Platform.assemble(ok, new ProcessSandbox(), Hitl.autoAllow(), LoopBudget.defaults());
        assertTrue(healthy.loop().run("s2", "any").ok());
    }

    @Test
    @DisplayName("整个 turn 的事件序列符合契约：input → turn → step → llm/tool → snapshot → closed")
    void eventSequenceContract() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("add", "{\"a\":1,\"b\":2}")
                .thenText("3");

        Platform p = platform(llm, Hitl.autoAllow(), LoopBudget.defaults());
        p.loop().run("s1", "算 1+2");

        var types = ((EventSourcedSessionLog) p.sessionLog()).events("s1")
                .stream().map(e -> e.type()).toList();

        assertEquals(SessionLog.EV_INPUT, types.get(0));
        assertEquals(SessionLog.EV_TURN_START, types.get(1));
        assertEquals(SessionLog.EV_CONTEXT_PREPARED, types.get(2));
        assertEquals(SessionLog.EV_STEP_START, types.get(3));
        assertTrue(types.contains(SessionLog.EV_TOOL_CALL));
        assertTrue(types.contains(SessionLog.EV_TOOL_RESULT));
        assertTrue(types.contains(SessionLog.EV_STATE_SNAPSHOT));
        assertEquals(SessionLog.EV_TURN_CLOSED, types.get(types.size() - 1));
    }
}
