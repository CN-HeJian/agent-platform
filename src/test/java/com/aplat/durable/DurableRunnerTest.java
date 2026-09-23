package com.aplat.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmChunk;
import com.aplat.seam.LlmRequest;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.DefaultToolPolicy;
import com.aplat.tools.DefaultToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * U19/U20 验收：任务状态机 + 检查点 + **断点续跑**。
 *
 * <h2>怎么在进程内"模拟崩溃"</h2>
 *
 * <p>抛一个 {@link Error} 而不是 {@link Exception}。这不是取巧，而是刻意对齐真实的死法：
 * {@code AgentLoop} 与 {@code ToolPipeline} 都只兜 {@code Exception}
 * （业务异常要转成观察回填，让模型自纠），而 {@code Error} 会**穿透它们**、
 * 一路冲出 {@code start()}——和进程被 {@code kill -9} 的表现完全一样：
 * 已经写库的状态留着，后面的步骤没了。
 *
 * <p>真正跨进程的验证不在这里，而是一次**真的 kill -9**：见 {@code run/CrashDemo}
 * 与 DEMO-OUTPUT.md 里的记录。单测证明逻辑，真机证明机制。
 */
class DurableRunnerTest {

    @TempDir
    Path tmp;

    /** 一个有可观察副作用的工具：每被真正执行一次，就往文件里追加一行。 */
    private final AtomicInteger executions = new AtomicInteger();

    private Tool sinkTool() {
        return Tool.of(ToolSpec.of("sink", "把一个标记写进文件（用于观察副作用次数）", "{}"),
                call -> {
                    executions.incrementAndGet();
                    try {
                        Files.writeString(tmp.resolve("sink.txt"),
                                "executed\n", StandardCharsets.UTF_8,
                                Files.exists(tmp.resolve("sink.txt"))
                                        ? java.nio.file.StandardOpenOption.APPEND
                                        : java.nio.file.StandardOpenOption.CREATE);
                    } catch (IOException e) {
                        return ToolResult.error(ToolResult.ERR_SANDBOX, e.getMessage());
                    }
                    return ToolResult.ok("sunk");
                });
    }

    /** 同上，但**先写文件再抛 Error**——模拟"副作用已发生、结果没回填"。 */
    private Tool sinkThenDieTool() {
        return Tool.of(ToolSpec.of("sink", "写文件然后模拟进程猝死", "{}"),
                call -> {
                    executions.incrementAndGet();
                    try {
                        Files.writeString(tmp.resolve("sink.txt"), "executed\n",
                                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                    } catch (IOException ignored) {
                        // 崩之前的写盘失败不重要，我们只是要留下痕迹
                    }
                    throw new Error("simulated sudden process death while the side effect was in flight");
                });
    }

    private int sinkLines() throws IOException {
        Path f = tmp.resolve("sink.txt");
        if (!Files.exists(f)) {
            return 0;
        }
        return (int) Files.readAllLines(f, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).count();
    }

    private Platform platform(ScriptedLlmAdapter llm, InMemoryStore store, Tool extra) {
        return Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), store,
                log -> Hitl.autoAllow(),
                reg -> reg.register(extra));
    }

    // ---------------------------------------------------------------- U19

    @Test
    @DisplayName("U19：提交落 PENDING，跑完落 SUCCEEDED，且步数与结果被记下来")
    void lifecycleGoesThroughToSucceeded() {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("sink", "{}")
                .thenText("做完了");
        Platform p = platform(llm, store, sinkTool());

        TaskRecord submitted = p.durable().submit("s1", "随便做点什么");
        assertEquals(TaskState.PENDING, submitted.state());

        TaskRecord done = p.durable().start(submitted.taskId());
        assertEquals(TaskState.SUCCEEDED, done.state());
        assertEquals(2, done.step());
        assertEquals(1, done.attempts());
        assertEquals("做完了", done.detail());
        assertEquals(1, executions.get());

        // 状态迁移在会话时间线里也看得见，且顺序就是它真正经历的顺序
        var log = (EventSourcedSessionLog) p.sessionLog();
        var states = log.ofType("s1", SessionLog.EV_TASK_STATE);
        assertEquals(List.of("submitted", "running", "succeeded"),
                states.stream().map(e -> e.str("phase")).toList(),
                "任务的一生（受理 → 接手 → 收口）必须各留一条，缺哪条都说明状态机漏了");
    }

    @Test
    @DisplayName("U19：终态不可重跑——FAILED 重跑会得到同样的结果，所以它是终态")
    void terminalTaskRefusesToRunAgain() {
        InMemoryStore store = new InMemoryStore();
        // 步数用尽 → FAILED
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("sink", "{}")
                .thenToolCall("sink", "{}")
                .thenToolCall("sink", "{}")
                .repeat();
        Platform p = Platform.assemble(llm, new ProcessSandbox(), new LoopBudget(2, 8_000),
                DefaultToolPolicy.defaults(), store, log -> Hitl.autoAllow(),
                reg -> reg.register(sinkTool()));

        TaskRecord task = p.durable().start(p.durable().submit("s1", "跑到预算用尽").taskId());
        assertEquals(TaskState.FAILED, task.state());
        assertTrue(task.detail().contains("exhausted"), task.detail());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> p.durable().start(task.taskId()));
        assertTrue(e.getMessage().contains("terminal"), e.getMessage());
    }

    @Test
    @DisplayName("U19：挂起可续、取消是终态")
    void suspendIsResumableButCancelIsNot() {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter().thenText("好").repeat();
        Platform p = platform(llm, store, sinkTool());

        String id = p.durable().submit("s1", "做个事").taskId();
        assertEquals(TaskState.SUSPENDED, p.durable().suspend(id).state());
        assertTrue(p.durable().require(id).state().resumable(), "挂起必须可续");

        assertEquals(TaskState.CANCELLED, p.durable().cancel(id).state());
        assertThrows(IllegalStateException.class, () -> p.durable().resume(id));
    }

    // ------------------------------------------------------- U20 核心验收

    @Test
    @DisplayName("U20 验收：崩在第二步 → 启动时认领为 CRASHED → 续跑从第二步接上，第一步不重跑")
    void crashThenReclaimThenResumeContinuesFromCheckpoint() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("sink", "{}")                                  // 步 1：真的执行一次副作用
                .then("die", req -> {                                        // 步 2：穿透出去，模拟猝死
                    throw new Error("process died here");
                })
                .thenText("第二步是续跑才走完的");                             // 续跑后的步 2
        Platform p = platform(llm, store, sinkTool());

        String taskId = p.durable().submit("s1", "两步活").taskId();

        // 崩在第二步：Error 穿透 start()，库里留着 RUNNING
        assertThrows(Error.class, () -> p.durable().start(taskId));
        assertEquals(TaskState.RUNNING, p.durable().require(taskId).state(),
                "崩掉的进程留下的正是 RUNNING —— 这就是「孤儿」的标记");
        assertEquals(1, sinkLines(), "第一步的副作用确实发生过一次");

        // 重启后第一件事：认领孤儿
        int reclaimed = p.durable().reclaimOrphans();
        assertEquals(1, reclaimed);
        TaskRecord crashed = p.durable().require(taskId);
        assertEquals(TaskState.CRASHED, crashed.state());
        assertEquals(1, crashed.step(), "崩溃前最近一次成功的检查点在步 1");

        // 续跑
        TaskRecord done = p.durable().resume(taskId);
        assertEquals(TaskState.SUCCEEDED, done.state());
        assertEquals("[resumed] 第二步是续跑才走完的", done.detail(),
                "结果里必须留下「这是续跑得来的」——否则查库时分不清它是跑完的还是接完的");

        // **核心断言**：副作用仍然只有一次。第一步没有被重跑（它的观察已在检查点里）。
        assertEquals(1, sinkLines(), "续跑绝不能重放已经完成的步骤");
        assertEquals(1, executions.get());

        var log = (EventSourcedSessionLog) p.sessionLog();
        assertEquals(1, log.ofType("s1", SessionLog.EV_TURN_RESUMED).size());
        assertTrue(log.ofType("s1", SessionLog.EV_TURN_RESUMED).get(0).payload()
                .get("fromStep").equals(2), "续跑必须从检查点的下一步开始");
        assertTrue(log.ofType("s1", SessionLog.EV_TURN_RESUMED).get(0).payload()
                .get("restoredMessages") instanceof Integer restored && restored > 0,
                "恢复的消息不能是空的，否则模型醒来会失忆");
    }

    @Test
    @DisplayName("U21 验收：崩在「副作用已发生、结果未回填」之间 → 续跑拦下不重跑")
    void crashInFlightSideEffectIsNotRepeated() throws Exception {
        InMemoryStore store = new InMemoryStore();
        // 脚本必须让模型在上一次崩溃的那一步**再发一次同样的调用**——否则这个用例什么也没证明。
        // 判据取"上下文里有没有工具观察"：没有就再发一次（同一个 call id，因为
        // ToolCall.of 是确定性的），有了就说明拿到的是被拦下的结果，于是收口。
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .then("retry-until-suppressed", req -> {
                    boolean sawObservation = req.messages().stream()
                            .anyMatch(m -> "tool".equals(m.role()));
                    return sawObservation
                            ? List.of(new LlmChunk.TextDelta("好，我不重跑"), new LlmChunk.Done("stop"))
                            : List.of(new LlmChunk.ToolCallDelta(ToolCall.of("sink", "{}")),
                                      new LlmChunk.Done("tool_calls"));
                })
                .repeat();
        Platform p = platform(llm, store, sinkThenDieTool());

        String taskId = p.durable().submit("s1", "一步活").taskId();
        assertThrows(Error.class, () -> p.durable().start(taskId));
        assertEquals(1, sinkLines(), "副作用已经发生了一次");

        p.durable().reclaimOrphans();
        TaskRecord done = p.durable().resume(taskId);

        // 第二步（崩溃的那一步）的检查点从未写下，所以续跑会重放这一步；
        // 幂等闸看到"已声明、未回填"，于是**拦下**而不是再跑一遍。
        assertEquals(TaskState.SUCCEEDED, done.state());
        assertEquals(1, sinkLines(), "结果未知时绝不能重跑副作用");
        assertEquals(1, executions.get(), "工具的真正执行次数必须还是 1");
        assertTrue(llm.requests().size() >= 3,
                "模型应当被问过至少三次（崩前 1 次 + 续跑 2 次），实际 " + llm.requests().size());

        var log = (EventSourcedSessionLog) p.sessionLog();
        var replayed = log.ofType("s1", SessionLog.EV_TOOL_REPLAYED);
        assertEquals(1, replayed.size(), "被拦下这件事必须留痕，否则日志里和「真跑过」一样");
        assertEquals("result-unknown", replayed.get(0).str("reason"));

        // 模型拿到的是 DUPLICATE_SUPPRESSED，它据此可以换个可核对的做法
        assertEquals(IdempotencyGuard.ERR_DUPLICATE_SUPPRESSED,
                log.ofType("s1", SessionLog.EV_TOOL_RESULT).get(0).str("errorCode"));
    }

    @Test
    @DisplayName("没有检查点时的续跑是「从头再来」，而且必须如实标出来")
    void resumeWithoutCheckpointSaysSo() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter().thenText("重来一遍").repeat();
        Platform p = platform(llm, store, sinkTool());

        String taskId = p.durable().submit("s1", "活").taskId();
        // 手工把状态改成 CRASHED（等价于"崩在第一步的检查点之前"）
        TaskRecord t = p.durable().require(taskId);
        store.put(DurableRunner.NS, taskId,
                t.with(TaskState.CRASHED, 0, 0, "no checkpoint").toJson());
        assertTrue(store.get(StoreCheckpointer.NS, taskId).isEmpty(),
                "这个用例的前提就是「一个检查点都没有」，前提不成立时它就不再证明什么");

        TaskRecord done = p.durable().resume(taskId);
        assertEquals(TaskState.SUCCEEDED, done.state());

        var log = (EventSourcedSessionLog) p.sessionLog();
        assertTrue(log.ofType("s1", SessionLog.EV_TURN_RESUMED).isEmpty(),
                "没有检查点时不该有 turn.resumed —— 那是「恢复成功」的信号，不能拿来充数");
        assertEquals("running(no-checkpoint)",
                log.ofType("s1", SessionLog.EV_TASK_STATE).get(1).str("phase"),
                "降级必须可见：把「从头再来」报成「恢复」会让人以为副作用只发生过一次");
    }

    @Test
    @DisplayName("多任务不串：同一会话里的两个任务各有自己的检查点")
    void tasksInSameSessionDoNotShareCheckpoints() {
        InMemoryStore store = new InMemoryStore();
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter().thenText("一").repeat();
        Platform p = platform(llm, store, sinkTool());

        TaskRecord a = p.durable().submit("s1", "任务 A");
        TaskRecord b = p.durable().submit("s1", "任务 B");
        assertNotNull(a.taskId());
        assertTrue(!a.taskId().equals(b.taskId()));
        assertEquals(2, p.durable().list().size());

        p.durable().start(a.taskId());
        p.durable().start(b.taskId());
        List<TaskRecord> all = p.durable().list();
        assertEquals(2, all.stream().filter(t -> t.state() == TaskState.SUCCEEDED).count());
    }
}
