package com.aplat.schedule;

import com.aplat.durable.DurableRunner;
import com.aplat.durable.TaskRecord;
import com.aplat.durable.TaskState;
import com.aplat.hitl.ScopedHitl;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 调度器（U22）：到点了就把一条**耐久任务**开起来。
 *
 * <p>它自己不存任何状态——每一条调度都在 {@link Store} 里，所以重启之后调度还在，
 * 而且"上次跑到哪、结果如何"也照旧看得见。它做决定只比两个数字：{@code nextRunAt} 与现在。
 *
 * <h2>四条必须写下来的取舍</h2>
 *
 * <p><b>1. 先推进 {@code nextRunAt}、再开跑。</b>
 * 反过来的话，进程崩在"跑完了、还没推进"之间，重启后会把**同一个时刻**再触发一次——
 * 于是那条调度每崩溃一次就多跑一遍。顺序反过来，最坏情况是"该跑的那次没跑"，
 * 而那是一次漏跑，不是无限重复。
 *
 * <p><b>2. 错过的一律不补跑。</b>
 * 下一次永远从 {@code now} 起算，而不是从"上次计划时刻"起算。
 * 补跑会把"停机两小时"变成"上线瞬间涌出 720 条任务"——对下游是事故，不是修复。
 * 漏掉的那几次在 {@code lastOutcome} 里有痕迹可查（{@code skipped-missed}）。
 *
 * <p><b>3. 上一次还没跑完就不再起一条。</b>
 * 调度周期比任务耗时短是常态（每 10 秒一次、单次跑 3 分钟）。不设这道闸的话，
 * 同一时刻会有几十个任务在跑同一个目标，而这在日志里表现得像"调度正常、任务很多"。
 *
 * <p><b>4. 单实例假设，和多实例下会发生什么。</b>
 * 这一版没有租约，两个实例同时跑同一份调度表 = 两条调度各触发一次。
 * 这跟 {@code DurableRunner.reclaimOrphans()} 是同一个假设，也是同一个待办（U33）。
 * 之所以不现在做：一个错的兜底比没有兜底更危险，而"每 10 秒跑两次"在还没有多实例之前
 * 只是一个假设中的问题。
 */
public final class Scheduler implements AutoCloseable {

    /** 调度记录的命名空间。 */
    public static final String NS = "schedule";

    private final DurableRunner durable;
    private final Store store;
    private final SessionLog log;
    private final ToolRegistry tools;
    private final LongSupplier clock;
    private final long tickMillis;

    /** 每个触发在**自己的虚拟线程**上跑：一条慢任务不该让别的调度错过时刻。 */
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong seq = new AtomicLong();
    private ScheduledExecutorService ticker;

    public Scheduler(DurableRunner durable, Store store, SessionLog log, ToolRegistry tools,
                     LongSupplier clock, long tickMillis) {
        this.durable = durable;
        this.store = store;
        this.log = log;
        this.tools = tools;
        this.clock = clock;
        this.tickMillis = tickMillis;
    }

    public Scheduler(DurableRunner durable, Store store, SessionLog log, ToolRegistry tools) {
        this(durable, store, log, tools, System::currentTimeMillis, 1000);
    }

    public String id() {
        return "scheduler[store]";
    }

    public long tickMillis() {
        return tickMillis;
    }

    // ------------------------------------------------------------------ 管理

    /** 建一条调度。返回的警告不算失败——但调用方**应当**把它打印出来。 */
    public Created create(String goal, String sessionId, Trigger trigger, List<String> preApprovedTools) {
        long now = clock.getAsLong();
        String id = "sch-" + now + "-" + seq.incrementAndGet();
        ScheduleSpec spec = ScheduleSpec.create(id, goal, sessionId, trigger, preApprovedTools, now);
        store.put(NS, id, spec.toJson());
        return new Created(spec, lintPreApproval(spec));
    }

    /**
     * 提前批准的警告：把"半夜无人确认就执行命令"这件事从隐含假设变成显式选择。
     *
     * <p>不阻止（有些场景就是要这样，比如运维自己写的一条日报），但必须说出来。
     */
    private List<String> lintPreApproval(ScheduleSpec spec) {
        List<String> warnings = new ArrayList<>();
        for (String name : spec.preApprovedTools()) {
            Optional<ToolSpec> found = tools == null ? Optional.empty() : tools.specs().stream()
                    .filter(s -> s.name().equals(name)).findFirst();
            if (found.isEmpty()) {
                warnings.add("调度 " + spec.scheduleId() + " 提前批准了工具 '" + name
                        + "'，但它现在并不存在：到点那次调用会因为 UNKNOWN_TOOL 失败。");
            } else if (found.get().executesCommands()) {
                warnings.add("调度 " + spec.scheduleId() + " 提前批准了**执行类**工具 '" + name
                        + "'（它会把参数当命令执行）：到点没有人在场，它会在无人确认的情况下跑命令。");
            }
        }
        return warnings;
    }

    public List<ScheduleSpec> list() {
        List<ScheduleSpec> out = new ArrayList<>();
        store.all(NS).values().forEach(json -> ScheduleSpec.fromJson(json).ifPresent(out::add));
        out.sort(java.util.Comparator.comparing(ScheduleSpec::nextRunAt));
        return out;
    }

    public Optional<ScheduleSpec> get(String scheduleId) {
        return store.get(NS, scheduleId).flatMap(ScheduleSpec::fromJson);
    }

    public boolean remove(String scheduleId) {
        if (get(scheduleId).isEmpty()) {
            return false;
        }
        store.remove(NS, scheduleId);
        return true;
    }

    public ScheduleSpec setEnabled(String scheduleId, boolean enabled) {
        ScheduleSpec spec = get(scheduleId).orElseThrow(
                () -> new IllegalArgumentException("no such schedule: " + scheduleId));
        long now = clock.getAsLong();
        ScheduleSpec out = enabled
                ? spec.withNextRun(spec.trigger().nextAfter(now).orElseThrow(() ->
                        new IllegalArgumentException("这条调度的触发器已经没有下一次了："
                                + spec.trigger().describe())), true)
                : spec.withNextRun(spec.nextRunAt(), false);
        store.put(NS, scheduleId, out.toJson());
        return out;
    }

    // ------------------------------------------------------------------ 触发

    /**
     * 手工立刻跑一次（不改 {@code nextRunAt}，也不算进"错过"）。
     *
     * <p>存在的理由：人想验证一条调度写对了没有，不该等到凌晨两点。
     */
    public TaskRecord runNow(String scheduleId) {
        ScheduleSpec spec = get(scheduleId).orElseThrow(
                () -> new IllegalArgumentException("no such schedule: " + scheduleId));
        TaskRecord task = durable.submit(spec.sessionId(), spec.goal());
        store.put(NS, scheduleId, spec.fired(task.taskId(), clock.getAsLong(),
                spec.enabled(), spec.nextRunAt()).toJson());
        runTask(spec, task.taskId());
        return durable.require(task.taskId());
    }

    /**
     * 扫一遍到期的调度。返回本轮**真正触发的**那些。
     *
     * <p>公开是为了让测试能不睡觉地驱动它（注入假时钟即可），生产上由 {@link #start()} 定时调用。
     */
    public List<ScheduleSpec> tick() {
        long now = clock.getAsLong();
        List<ScheduleSpec> fired = new ArrayList<>();
        for (ScheduleSpec spec : list()) {
            if (!spec.enabled() || spec.nextRunAt() > now) {
                continue;
            }
            if (stillRunning(spec)) {
                // 上一次还没跑完：跳过，但把调度推进到下一格——否则它会一直"到期"，
                // 每一轮都来问一次，日志被刷满而问题无人看见。
                advance(spec, now, "skipped-still-running");
                log.append(spec.sessionId(), SessionLog.EV_SCHEDULE_SKIPPED,
                        Map.of("scheduleId", spec.scheduleId(), "reason", "previous-run-still-running",
                                "lastTaskId", String.valueOf(spec.lastTaskId())));
                continue;
            }

            // 错过的那些：只在第一次把 nextRunAt 从"过去"推到"未来"时记一笔。
            boolean missed = spec.nextRunAt() + tickMillis < now;

            // 1) 先推进，再开跑（取舍 1）
            OptionalLong next = spec.trigger().nextAfter(now);
            long nextRunAt = next.orElse(0);
            boolean stillEnabled = next.isPresent();
            TaskRecord task = durable.submit(spec.sessionId(), spec.goal());
            ScheduleSpec updated = spec.fired(task.taskId(), now, stillEnabled, nextRunAt);
            store.put(NS, spec.scheduleId(), updated.toJson());
            fired.add(updated);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scheduleId", spec.scheduleId());
            payload.put("taskId", task.taskId());
            payload.put("trigger", spec.trigger().describe());
            payload.put("nextRunAt", nextRunAt);
            payload.put("missedRuns", missed ? "some (not replayed)" : "none");
            payload.put("preApprovedTools", spec.preApprovedTools());
            log.append(spec.sessionId(), SessionLog.EV_SCHEDULE_FIRED, payload);

            // 2) 在自己的虚拟线程上跑（取舍 3 的另一半：不阻塞 tick）
            runner.submit(() -> runTask(spec, task.taskId()));
        }
        return fired;
    }

    private void advance(ScheduleSpec spec, long now, String outcome) {
        OptionalLong next = spec.trigger().nextAfter(now);
        store.put(NS, spec.scheduleId(),
                spec.finished(outcome, now).withNextRun(next.orElse(0), next.isPresent()).toJson());
    }

    /** 上一次开出去的任务还在跑吗？ */
    private boolean stillRunning(ScheduleSpec spec) {
        if (spec.lastTaskId() == null || spec.lastTaskId().isBlank()) {
            return false;
        }
        return durable.get(spec.lastTaskId())
                .map(t -> t.state() == TaskState.RUNNING)
                .orElse(false);
    }

    /**
     * 真正跑一条：在**受限于该调度**的提前批准范围内执行。
     *
     * <p>{@link ScopedHitl} 的作用域只覆盖这一条线程，所以"这条调度免确认"不会漏到别的会话上。
     */
    private void runTask(ScheduleSpec spec, String taskId) {
        try (var ignored = ScopedHitl.scope(spec.preApprovedTools())) {
            TaskRecord done = durable.start(taskId);
            store.put(NS, spec.scheduleId(),
                    get(spec.scheduleId()).orElse(spec).finished(done.state().name(), clock.getAsLong())
                            .toJson());
        } catch (Throwable t) {
            // 调度线程里绝不能让异常逃出去：它会悄无声息地杀掉这一条调度的后续触发。
            store.put(NS, spec.scheduleId(),
                    get(spec.scheduleId()).orElse(spec)
                            .finished("THREW:" + t.getClass().getSimpleName(), clock.getAsLong()).toJson());
            log.append(spec.sessionId(), SessionLog.EV_ERROR,
                    Map.of("scheduleId", spec.scheduleId(), "phase", "scheduled-run",
                            "error", String.valueOf(t)));
        }
    }

    // ------------------------------------------------------------------ 启停

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aplat-scheduler");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::safeTick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // 定时线程里一次异常会让整个 scheduleWithFixedDelay **永久停摆**（这是它的规范）。
            // 所以必须吞掉，否则"某一次 tick 出了个错"会变成"调度从此再也不工作"，且没有任何提示。
            log.append("_scheduler", SessionLog.EV_ERROR,
                    Map.of("phase", "tick", "error", String.valueOf(t)));
        }
    }

    public boolean started() {
        return started.get();
    }

    public void stop() {
        started.set(false);
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    @Override
    public void close() {
        stop();
        runner.shutdown();
    }

    /** 新建调度的结果：记录 + 该说出来的警告。 */
    public record Created(ScheduleSpec spec, List<String> warnings) {
    }
}
