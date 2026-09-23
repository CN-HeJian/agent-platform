package com.aplat.run;

import com.aplat.durable.Checkpointer;
import com.aplat.durable.DurableRunner;
import com.aplat.durable.TaskRecord;
import com.aplat.durable.TaskState;
import com.aplat.loop.LoopBudget;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.LlmChunk;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.JdbcStore;
import com.aplat.store.StoreFactory;
import com.aplat.tools.DefaultToolPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实跨进程崩溃恢复演示（U19/U20/U21）。
 *
 * <p>单测里用 {@code throw new Error(...)} 模拟猝死，那证明的是**逻辑**；
 * 这里用 {@link Runtime#halt(int)} 让进程**当场消失**（不走关机钩子、不执行 finally、
 * 缓冲区不回刷），证明的是**机制**——两个进程、一个共享数据库、一个能数行数的文件。
 *
 * <pre>
 *   # U20：崩在两步之间 → 续跑从检查点接上，第一步的副作用不重放
 *   ./mvnw -q compile exec:java@crash -Dexec.args="u20 crash r1"
 *   ./mvnw -q compile exec:java@crash -Dexec.args="u20 resume r1"
 *
 *   # U21：崩在「副作用已发生、结果未回填」之间 → 续跑拦下，不重跑
 *   ./mvnw -q compile exec:java@crash -Dexec.args="u21 crash r1"
 *   ./mvnw -q compile exec:java@crash -Dexec.args="u21 resume r1"
 * </pre>
 *
 * <p>必须先设 {@code APLAT_DB_URL}：跨进程验证的前提是**两个进程看到同一份状态**，
 * 内存实现下这个演示什么都不证明，所以这里直接拒绝启动而不是跑出一个好看的假结果。
 * 副作用用文件行数来数，也是同一个理由：进程内的计数器活不过第一次 {@code halt}。
 */
public final class CrashDemo {

    /** 副作用落在这里。跨进程唯一可信的"执行了几次"就是它有几行。 */
    private static Path sinkFile;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: CrashDemo <u20|u21> <crash|resume> <runId>");
            System.exit(2);
            return;
        }
        String scenario = args[0];
        String phase = args[1];
        String runId = args[2];

        Store store = StoreFactory.fromEnv();
        if (!(store instanceof JdbcStore jdbc)) {
            System.err.println("[拒绝运行] 现在用的是 " + store.id() + "（内存）。");
            System.err.println("           跨进程演示必须有一个共享存储，否则两个进程各看各的状态，");
            System.err.println("           演示会「成功」但什么也没证明。请先设置：");
            System.err.println("           export APLAT_DB_URL='jdbc:mysql://127.0.0.1:3307/aplat'");
            System.err.println("           export APLAT_DB_USER=root APLAT_DB_PASSWORD=");
            System.exit(2);
            return;
        }

        // 任务 id 里必须带 runId：幂等键是持久化的，用固定 id 重跑会读到上一轮的 @pending，
        // 于是第二次演示"恰好"也被拦下——那看起来像成功，实际是脏数据。
        String taskId = "t-crash-" + scenario + "-" + runId;
        String sessionId = "s-crash-" + scenario + "-" + runId;
        sinkFile = Path.of(System.getProperty("java.io.tmpdir"),
                "aplat-crash-sink-" + scenario + "-" + runId + ".txt");

        System.out.println("=== CrashDemo " + scenario + " / " + phase + " / " + runId + " ===");
        System.out.println("Store   : " + jdbc.id() + " @ " + jdbc.url());
        System.out.println("Task    : " + taskId);
        System.out.println("Sink    : " + sinkFile);

        Platform platform = assemble(scenario, phase, store);

        if ("crash".equals(phase)) {
            runCrashPhase(platform, scenario, taskId, sessionId);
        } else {
            runResumePhase(platform, scenario, taskId, sessionId);
        }
        platform.close();
    }

    // ------------------------------------------------------------------ 装配

    private static Platform assemble(String scenario, String phase, Store store) {
        // 崩溃阶段的脚本 vs 续跑阶段的脚本不一样，这是刻意的：
        // 续跑进程醒来时应该"接着往下做"，而不是把崩溃那一步再做一次
        // （U20 的续跑从检查点的下一步开始，压根不会再被问到那一步）。
        LlmAdapter llm = "u21".equals(scenario)
                ? u21Script(phase)
                : u20Script(phase);

        return Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), store, log -> Hitl.autoAllow(),
                reg -> {
                    reg.register(sinkTool());
                    reg.register(sinkThenDieTool());
                    reg.register(dieTool());
                });
    }

    /** U20 脚本：第一步做副作用，第二步发起一个"会当场弄死进程"的调用。 */
    private static LlmAdapter u20Script(String phase) {
        if ("crash".equals(phase)) {
            return new ScriptedLlmAdapter()
                    .thenToolCall("sink", "{}")   // 步 1：副作用 + 步末写检查点
                    .thenToolCall("die", "{}")    // 步 2：halt(9)，检查点永远写不下来
                    .repeat();
        }
        // 续跑进程从检查点的**下一步**（第 2 步）开始，所以这里第一步就该是收口文本
        return new ScriptedLlmAdapter().thenText("第二步是续跑才走完的").repeat();
    }

    /**
     * U21 脚本：无论被问几次都发**同一个** call id，直到上下文里出现工具观察。
     *
     * <p>"同一个 call id"是这个用例成立的关键——幂等键里带 call id，
     * 换一个 id 就等于换一个副作用，闸门当然拦不住（也不该拦）。
     */
    private static LlmAdapter u21Script(String phase) {
        return new ScriptedLlmAdapter()
                .then("retry-until-suppressed", req -> {
                    boolean sawObservation = req.messages().stream()
                            .anyMatch(m -> "tool".equals(m.role()));
                    return sawObservation
                            ? List.of(new LlmChunk.TextDelta("好，我不重跑"),
                                      new LlmChunk.Done("stop"))
                            : List.of(new LlmChunk.ToolCallDelta(
                                          ToolCall.of("crash_after_sink", "{}")),
                                      new LlmChunk.Done("tool_calls"));
                })
                .repeat();
    }

    // ------------------------------------------------------------------ 工具

    /** 有可观察副作用的工具：每真正执行一次就往文件里追一行。 */
    private static Tool sinkTool() {
        return Tool.of(ToolSpec.of("sink", "往文件里追加一行（用于数副作用次数）", "{}"),
                call -> {
                    appendSink();
                    return ToolResult.ok("sunk");
                });
    }

    /** 副作用先发生、再当场弄死进程——精确复刻"崩在副作用之后、回填之前"。 */
    private static Tool sinkThenDieTool() {
        return Tool.of(ToolSpec.of("crash_after_sink",
                        "先写文件再让进程当场消失（模拟最危险的崩点）", "{}"),
                call -> {
                    appendSink();
                    System.out.println("[tool] 副作用已落盘（" + sinkFile + "），现在让进程当场消失……");
                    System.out.flush();
                    Runtime.getRuntime().halt(9);
                    throw new IllegalStateException("halt 之后不可达");
                });
    }

    /** 只弄死进程，不做副作用：用来把崩溃点放到"某一步做完之后"。 */
    private static Tool dieTool() {
        return Tool.of(ToolSpec.of("die", "让进程当场消失（模拟猝死）", "{}"),
                call -> {
                    System.out.println("[tool] 检查点已在第 1 步写下，现在让进程当场消失……");
                    System.out.flush();
                    Runtime.getRuntime().halt(9);
                    throw new IllegalStateException("halt 之后不可达");
                });
    }

    private static void appendSink() {
        try {
            if (!Files.exists(sinkFile)) {
                Files.createDirectories(sinkFile.getParent());
            }
            Files.writeString(sinkFile, "executed\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("副作用写盘失败：" + e.getMessage(), e);
        }
    }

    private static int sinkLines() {
        if (!Files.exists(sinkFile)) {
            return 0;
        }
        try {
            return (int) Files.readAllLines(sinkFile, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 阶段

    private static void runCrashPhase(Platform p, String scenario, String taskId, String sessionId)
            throws IOException {
        // 清掉可能残留的同名任务（同一个 runId 被重复演示时）
        p.store().remove(DurableRunner.NS, taskId);
        p.durable().checkpointer().clear(taskId);
        Files.deleteIfExists(sinkFile);

        storeTask(p, TaskRecord.pending(taskId, sessionId, "崩溃恢复演示 " + scenario));
        System.out.println("已受理任务，开始执行……（进程应当在这一步里死掉，不会打印「收口」）");

        p.durable().start(taskId); // 这里应当永远走不到——进程会在工具里 halt
        System.err.println("[异常] 进程居然活下来了：说明崩溃点没生效，这次演示无效");
        System.exit(3);
    }

    private static void runResumePhase(Platform p, String scenario, String taskId, String sessionId) {
        System.out.println();
        System.out.println("--- 崩溃后库里留下的东西（另一个进程读同一份 MySQL）---");
        TaskRecord orphan = p.durable().require(taskId);
        System.out.println("  state  = " + orphan.state() + "   （RUNNING = 执行者已经不在了）");
        System.out.println("  step   = " + orphan.step() + "   ← 任务记录里的值。它只在认领与收口时写入，"
                + "真正的进度看下面");
        Checkpointer.Checkpoint cp = p.durable().checkpointer().latest(taskId).orElse(null);
        System.out.println("  checkpoint = " + (cp == null
                ? "无（崩在检查点之前，只能从头来）"
                : "step " + cp.step() + "，带 " + cp.messages().size() + " 条消息"));
        var log0 = (EventSourcedSessionLog) p.sessionLog();
        var events0 = log0.events(sessionId);
        System.out.println("  事件流 = " + events0.size() + " 条，seq "
                + (events0.isEmpty() ? "-" : events0.get(0).seq() + ".."
                        + events0.get(events0.size() - 1).seq())
                + "   ← 崩溃进程写的那一半也在这里，序号是接着往下走的");
        System.out.println("  sink   = " + sinkLines() + " 行");

        int reclaimed = p.durable().reclaimOrphans();
        System.out.println();
        System.out.println("认领孤儿 " + reclaimed + " 条 → " + p.durable().require(taskId).state());

        int before = sinkLines();
        TaskRecord done = p.durable().resume(taskId);
        int after = sinkLines();

        System.out.println();
        System.out.println("--- 续跑结果 ---");
        System.out.println("  state  = " + done.state());
        System.out.println("  step   = " + done.step() + "（累计）");
        System.out.println("  attempts = " + done.attempts());
        System.out.println("  detail = " + done.detail());
        System.out.println("  sink   = " + before + " → " + after + " 行");
        System.out.println("  结论   : " + (after == 1
                ? "✓ 副作用总共只发生过一次 —— 已经完成的步骤没有被重放"
                : "✗ 副作用发生了 " + after + " 次 —— 有步骤被重放了"));

        var log = (EventSourcedSessionLog) p.sessionLog();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var e : log.events(sessionId)) {
            counts.merge(e.type(), 1, Integer::sum);
        }
        System.out.println();
        System.out.println("--- 这次会话的事件统计 ---");
        counts.forEach((k, v) -> System.out.println("  " + k + " = " + v));
        System.out.println("  turn.resumed = " + counts.getOrDefault(SessionLog.EV_TURN_RESUMED, 0)
                + "（0 表示这次没走检查点，是从头来的——降级也必须是可见的）");
        System.out.println();
        System.out.println(after == 1 && done.state() == TaskState.SUCCEEDED
                ? "演示通过。" : "演示未通过，请看上面的数字。");
    }

    private static void storeTask(Platform p, TaskRecord task) {
        p.store().put(DurableRunner.NS, task.taskId(), task.toJson());
    }
}
