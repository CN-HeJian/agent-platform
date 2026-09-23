package com.aplat.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.durable.DurableRunner;
import com.aplat.durable.TaskRecord;
import com.aplat.durable.TaskState;
import com.aplat.hitl.InteractiveHitl;
import com.aplat.hitl.ScopedHitl;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.SessionLog;
import com.aplat.seam.ToolResult;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.DefaultToolPolicy;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U22 验收：调度器。
 *
 * <p>全部用**注入的假时钟**驱动 {@code tick()}，一次也不睡觉。理由不只是快：
 * 真时钟下"错过两小时"这个用例要跑两小时，于是它不会被写出来——而"停机后补跑"
 * 恰恰是调度最容易出事的地方。
 */
class SchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static long at(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), ZONE)
                .toInstant().toEpochMilli();
    }

    private final InMemoryStore store = new InMemoryStore();
    private final AtomicLong clock = new AtomicLong(at("2026-09-23T09:00:00"));
    private Platform platform;
    private Scheduler scheduler;

    /** 一个"会问人、但没人应答就不等"的平台：用来验提前批准的路径。 */
    private void assemble(Duration hitlTimeout) {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"echo scheduled\"}")
                .thenText("做完了")
                .repeat();
        platform = Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), store,
                log -> new ScopedHitl(new InteractiveHitl(log, InteractiveHitl.Mode.ASK, hitlTimeout), log));
        scheduler = new Scheduler(platform.durable(), store, platform.sessionLog(), platform.tools(),
                clock::get, 1000);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
        if (platform != null) {
            platform.close();
        }
    }

    // ------------------------------------------------------------------ 基础

    @Test
    @DisplayName("建调度时就把首次触发时刻算好存下来（不是每次读的时候现算）")
    void createComputesFirstRun() {
        assemble(Duration.ZERO);
        Scheduler.Created created = scheduler.create("跑日报", "s1", new Trigger.Interval(60), List.of());

        assertEquals(clock.get() + 60_000, created.spec().nextRunAt());
        assertTrue(created.warnings().isEmpty());

        // 存下来的：换一个调度器实例，看到的还是它（等价于重启）
        Scheduler other = new Scheduler(platform.durable(), store, platform.sessionLog(),
                platform.tools(), clock::get, 1000);
        assertEquals(1, other.list().size());
        assertEquals(created.spec().nextRunAt(), other.list().get(0).nextRunAt());
    }

    @Test
    @DisplayName("到点触发一次，并推进；同一时刻再 tick 不会重复触发")
    void tickFiresOnce() throws Exception {
        assemble(Duration.ZERO);
        String id = scheduler.create("跑日报", "s1", new Trigger.Interval(60), List.of()).spec().scheduleId();

        // 还没到点
        assertTrue(scheduler.tick().isEmpty());

        clock.set(clock.get() + 60_000);
        List<ScheduleSpec> fired = scheduler.tick();
        assertEquals(1, fired.size());
        assertEquals(clock.get() + 60_000, fired.get(0).nextRunAt());
        assertEquals(1, fired.get(0).runCount());

        // 同一时刻再 tick：不该再触发（nextAfter 是"严格晚于"）
        assertTrue(scheduler.tick().isEmpty(), "同一时刻不能被触发两次");

        waitForOutcome(id);
        assertEquals("SUCCEEDED", scheduler.get(id).orElseThrow().lastOutcome());
    }

    @Test
    @DisplayName("停机后不补跑：时钟跳 3 个间隔只跑一次，下一次从 now 起算")
    void missedRunsAreNotReplayed() {
        assemble(Duration.ZERO);
        String id = scheduler.create("跑日报", "s1", new Trigger.Interval(60), List.of()).spec().scheduleId();

        // 第一个间隔正常触发
        clock.set(clock.get() + 60_000);
        assertEquals(1, scheduler.tick().size());

        // 停机两小时（tick 一次都没被调用）
        clock.set(clock.get() + 2 * 3600_000L);
        List<ScheduleSpec> fired = scheduler.tick();
        assertEquals(1, fired.size(), "两小时的停机只换来**一次**触发，不是 120 次");
        assertEquals(clock.get() + 60_000, fired.get(0).nextRunAt(),
                "下一次必须从 now 起算——从「上次计划时刻」起算就是把停机变成一阵风暴");
        assertTrue(scheduler.tick().isEmpty());
    }

    @Test
    @DisplayName("一次性调度跑完自己停用（不留一条永远不再触发的僵尸记录）")
    void onceDisablesItself() {
        assemble(Duration.ZERO);
        long when = clock.get() + 30_000;
        String id = scheduler.create("延迟跑一次", "s1", new Trigger.Once(when), List.of())
                .spec().scheduleId();
        assertEquals(when, scheduler.get(id).orElseThrow().nextRunAt());

        clock.set(when);
        List<ScheduleSpec> fired = scheduler.tick();
        assertEquals(1, fired.size());
        assertFalse(scheduler.get(id).orElseThrow().enabled(),
                "一次性触发之后应当自动停用");
        assertFalse(fired.get(0).enabled());

        clock.set(clock.get() + 3600_000L);
        assertTrue(scheduler.tick().isEmpty(), "停用之后永远不该再触发");
    }

    @Test
    @DisplayName("上一次还没跑完就不再起一条（调度周期比任务耗时短是常态）")
    void doesNotStackWhilePreviousStillRunning() {
        assemble(Duration.ZERO);
        // 手工造一条"正在跑"的任务，避免用真线程去比时序
        TaskRecord running = TaskRecord.pending("t-busy", "s1", "上一次")
                .with(TaskState.RUNNING, 1, 1, "");
        store.put(DurableRunner.NS, "t-busy", running.toJson());

        String id = "sch-busy";
        ScheduleSpec spec = ScheduleSpec.create(id, "跑日报", "s1", new Trigger.Interval(60),
                List.of(), clock.get());
        // 上一轮 {时钟 - 60s} 开出去的任务就是这条还在跑的
        store.put(Scheduler.NS, id, spec.fired("t-busy", clock.get() - 60_000, true, clock.get()).toJson());

        List<ScheduleSpec> fired = scheduler.tick();
        assertTrue(fired.isEmpty(), "上一次没跑完时不该再起一条");
        // 但要推进时刻，否则它会每一轮都来问一次、把日志刷满
        assertTrue(scheduler.get(id).orElseThrow().nextRunAt() > clock.get());
        assertEquals("skipped-still-running", scheduler.get(id).orElseThrow().lastOutcome());

        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertEquals(1, log.ofType("s1", SessionLog.EV_SCHEDULE_SKIPPED).size(),
                "跳过必须留痕：不留痕的话「安静地坏着」看起来和「什么都没发生」一样");
    }

    @Test
    @DisplayName("手工立刻跑一次：同步返回结果，但不动 nextRunAt")
    void runNowDoesNotShiftSchedule() throws Exception {
        assemble(Duration.ZERO);
        Scheduler.Created created = scheduler.create("跑日报", "s1", new Trigger.Interval(600), List.of());
        long plannedNext = created.spec().nextRunAt();

        TaskRecord done = scheduler.runNow(created.spec().scheduleId());
        assertEquals(TaskState.SUCCEEDED, done.state());
        assertEquals(plannedNext, scheduler.get(created.spec().scheduleId()).orElseThrow().nextRunAt(),
                "手工跑一次不该改变既有的触发节奏");
        assertEquals(1, scheduler.get(created.spec().scheduleId()).orElseThrow().runCount());
    }

    @Test
    @DisplayName("删除与停用/启用")
    void removeAndToggle() {
        assemble(Duration.ZERO);
        String id = scheduler.create("跑日报", "s1", new Trigger.Interval(60), List.of())
                .spec().scheduleId();

        assertFalse(scheduler.setEnabled(id, false).enabled());
        clock.set(clock.get() + 600_000L);
        assertTrue(scheduler.tick().isEmpty(), "停用期间到点也不该触发");

        long newNext = scheduler.setEnabled(id, true).nextRunAt();
        assertTrue(newNext > clock.get(), "重新启用时下一次应当从「现在」起算");

        assertTrue(scheduler.remove(id));
        assertFalse(scheduler.remove(id), "重复删除返回 false 而不是抛");
        assertTrue(scheduler.list().isEmpty());
    }

    // -------------------------------------------------------- 提前批准（U22 的核心）

    @Test
    @DisplayName("提前批准的调度：凌晨没人也能跑通，并且留痕写明「是调度批的」")
    void preApprovedScheduleRunsWithoutAHuman() throws Exception {
        assemble(Duration.ZERO); // HITL 是 ask 模式、且"不等人"
        String id = scheduler.create("跑个命令", "s1", new Trigger.Interval(60), List.of("shell"))
                .spec().scheduleId();

        clock.set(clock.get() + 60_000);
        scheduler.tick();
        waitForOutcome(id);

        assertEquals("SUCCEEDED", scheduler.get(id).orElseThrow().lastOutcome());

        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertEquals(1, log.ofType("s1", SessionLog.EV_HITL_PREAPPROVED).size());
        assertEquals("shell", log.ofType("s1", SessionLog.EV_HITL_PREAPPROVED).get(0).str("tool"));
        assertTrue(log.ofType("s1", SessionLog.EV_HITL_PREAPPROVED).get(0).str("args")
                .contains("echo scheduled"), "事件里要带上被批准的是什么参数");

        // 工具真的执行了（而不是被超时挡下）
        var toolResults = log.ofType("s1", SessionLog.EV_TOOL_RESULT);
        assertEquals(1, toolResults.size());
        assertEquals("true", toolResults.get(0).str("ok"), "提前批准之后工具应当真的执行成功");
    }

    @Test
    @DisplayName("不提前批准：到点没人应答就走超时路径——任务算跑完，但那个命令**没有**执行")
    void withoutPreApprovalTheCommandIsNotRun() throws Exception {
        assemble(Duration.ZERO);
        String id = scheduler.create("跑个命令", "s1", new Trigger.Interval(60), List.of())
                .spec().scheduleId();

        clock.set(clock.get() + 60_000);
        scheduler.tick();
        waitForOutcome(id);

        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertTrue(log.ofType("s1", SessionLog.EV_HITL_PREAPPROVED).isEmpty());
        var toolResults = log.ofType("s1", SessionLog.EV_TOOL_RESULT);
        assertEquals(1, toolResults.size());
        assertEquals("false", toolResults.get(0).str("ok"),
                "没人应答时命令不该被执行——「不问就做」和「问了没人答就做」是两件事");
        assertEquals(ToolResult.ERR_TIMEOUT, toolResults.get(0).str("errorCode"));
    }

    @Test
    @DisplayName("只批准名单里的工具：名单外的照旧要问（豁免不是「这条调度免检」）")
    void preApprovalIsPerTool() throws Exception {
        assemble(Duration.ZERO);
        String id = scheduler.create("跑个命令", "s1", new Trigger.Interval(60), List.of("add"))
                .spec().scheduleId();

        clock.set(clock.get() + 60_000);
        scheduler.tick();
        waitForOutcome(id);

        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertTrue(log.ofType("s1", SessionLog.EV_HITL_PREAPPROVED).isEmpty(),
                "批的是 add，不是 shell");
        assertEquals("false", log.ofType("s1", SessionLog.EV_TOOL_RESULT).get(0).str("ok"));
    }

    @Test
    @DisplayName("提前批准一个执行类工具时必须发出警告——把「半夜无人确认就跑命令」变成显式选择")
    void preApprovingAnExecutingToolWarns() {
        assemble(Duration.ZERO);
        Scheduler.Created created = scheduler.create("跑个命令", "s1", new Trigger.Interval(60),
                List.of("shell"));
        assertEquals(1, created.warnings().size(), created.warnings().toString());
        assertTrue(created.warnings().get(0).contains("执行类"), created.warnings().get(0));

        // 纯计算类工具不该有警告
        Scheduler.Created clean = scheduler.create("算个数", "s1", new Trigger.Interval(60),
                List.of("add"));
        assertTrue(clean.warnings().isEmpty(), clean.warnings().toString());

        // 不存在的工具也要喊——否则到点那次调用会以 UNKNOWN_TOOL 失败，而那时没人在看
        Scheduler.Created bogus = scheduler.create("算个数", "s1", new Trigger.Interval(60),
                List.of("不存在的工具"));
        assertEquals(1, bogus.warnings().size());
        assertTrue(bogus.warnings().get(0).contains("并不存在"));
    }

    @Test
    @DisplayName("触发器已经过点的一次性调度不许建（否则是一条永远不会跑的空记录）")
    void createRejectsPastOneShot() {
        assemble(Duration.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.create("补跑", "s1", new Trigger.Once(clock.get() - 1000), List.of()));
    }

    /** 触发之后任务在自己的虚拟线程上跑，这里等它落地。最多等 10 秒。 */
    private void waitForOutcome(String scheduleId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String outcome = scheduler.get(scheduleId).orElseThrow().lastOutcome();
            if (!"PENDING".equals(outcome) && !outcome.isBlank()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("调度 " + scheduleId + " 的这一次执行没有在 10 秒内收口");
    }
}
