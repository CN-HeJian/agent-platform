package com.aplat.durable;

import com.aplat.loop.AgentLoop;
import com.aplat.loop.TurnResult;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 耐久执行器（U19 + U20）：把"跑一次 Agent 循环"变成"一条可查、可恢复、可取消的任务"。
 *
 * <p>它只做三件事，都围绕一个前提：**状态是写下来的事实，不是算出来的**。
 *
 * <ol>
 *   <li><b>接手前先落状态</b>（{@code RUNNING}），跑完再落终态。于是"库里有一条 RUNNING"
 *       就等价于"它的执行者已经不在了"——这正是 {@link #reclaimOrphans()} 的依据。</li>
 *   <li><b>续跑从检查点接</b>，而不是从头。检查点是**完整消息列表**，
 *       所以模型醒来时看到的上下文与崩溃前一致。</li>
 *   <li><b>状态迁移也落会话事件</b>（{@code task.state}），
 *       于是任务的一生在时间线里是可见的，而不只存在于任务表里。</li>
 * </ol>
 *
 * <h2>为什么状态迁移要先写库再干活</h2>
 *
 * <p>反过来（先干活、成功后再写 RUNNING）看着更"省一次写"，但会丢掉整个机制的意义：
 * 崩在活干到一半时，库里还是 {@code PENDING}——那意味着**没人知道它动过手脚**。
 * 先写 RUNNING，最坏情况是"活没干但被标成跑过"，那只是需要重跑一次；
 * 反过来则是"活干了一半但看着像没开始"，那才是真正会造成不一致的方向。
 */
public final class DurableRunner {

    /** 任务记录的命名空间。 */
    public static final String NS = "task";

    private final AgentLoop loop;
    private final Store store;
    private final Checkpointer checkpointer;
    private final AtomicLong seq = new AtomicLong();

    public DurableRunner(AgentLoop loop, Store store, Checkpointer checkpointer) {
        this.loop = loop;
        this.store = store;
        this.checkpointer = checkpointer;
    }

    public Checkpointer checkpointer() {
        return checkpointer;
    }

    // ---------------------------------------------------------------- 受理

    /**
     * 受理一条任务（落到 {@code PENDING}）。
     *
     * <p>id 用"时间戳 + 进程内序号"而不是纯自增：纯自增在**重启后会撞号**，
     * 而任务 id 是主键——撞号意味着新任务会覆盖掉一条旧任务，且没有任何报错。
     */
    public TaskRecord submit(String sessionId, String goal) {
        String taskId = "t-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        TaskRecord task = TaskRecord.pending(taskId, sessionId, goal);
        save(task);
        event(sessionId, task, "submitted");
        return task;
    }

    public Optional<TaskRecord> get(String taskId) {
        return store.get(NS, taskId).flatMap(TaskRecord::fromJson);
    }

    public List<TaskRecord> list() {
        List<TaskRecord> out = new ArrayList<>();
        store.all(NS).values().forEach(json -> TaskRecord.fromJson(json).ifPresent(out::add));
        out.sort(Comparator.comparingLong(TaskRecord::createdAt).thenComparing(TaskRecord::taskId));
        return out;
    }

    public TaskRecord require(String taskId) {
        return get(taskId).orElseThrow(
                () -> new IllegalArgumentException("no such task: " + taskId));
    }

    // ---------------------------------------------------------------- 执行

    /** 从零开始跑（或从 {@code PENDING}/{@code CRASHED} 接手）。 */
    public TaskRecord start(String taskId) {
        TaskRecord task = require(taskId);
        guardRunnable(task, "start");
        checkpointer.clear(taskId); // 新的一轮从零开始，别读到上一轮的残留
        TaskRecord running = save(task.with(TaskState.RUNNING, task.step(), task.attempts() + 1, ""));
        event(running.sessionId(), running, "running");
        TurnResult result = loop.run(running.sessionId(), List.of(), running.goal(), taskId);
        return finish(running, result, false);
    }

    /**
     * 续跑。有检查点就从它的下一步接着跑，没有就只能从头——**并且会说清楚这一点**。
     *
     * <p>降级必须可见：把"没有检查点、从头再来"也报成"恢复成功"，
     * 就等于让调用方以为副作用只发生过一次。
     */
    public TaskRecord resume(String taskId) {
        TaskRecord task = require(taskId);
        if (!task.state().resumable()) {
            throw new IllegalStateException("task " + taskId + " is " + task.state()
                    + " — 终态不重跑（FAILED 重跑会得到同样的结果；要重来请新建任务）");
        }
        Optional<Checkpointer.Checkpoint> cp = checkpointer.latest(taskId);
        int from = cp.map(Checkpointer.Checkpoint::step).orElse(task.step());
        TaskRecord running = save(task.with(TaskState.RUNNING, from, task.attempts() + 1, ""));
        event(running.sessionId(), running, cp.isEmpty() ? "running(no-checkpoint)" : "resumed");

        TurnResult result = cp
                .map(checkpoint -> loop.resume(running.sessionId(), checkpoint, taskId))
                .orElseGet(() -> loop.run(running.sessionId(), List.of(), running.goal(), taskId));
        return finish(running, result, cp.isPresent());
    }

    /** 人为挂起（可再续跑）。 */
    public TaskRecord suspend(String taskId) {
        TaskRecord task = require(taskId);
        if (task.state().terminal()) {
            throw new IllegalStateException("task " + taskId + " is already " + task.state());
        }
        TaskRecord suspended = save(task.with(TaskState.SUSPENDED, task.step(), task.attempts(),
                "suspended by operator"));
        event(suspended.sessionId(), suspended, "suspended");
        return suspended;
    }

    /** 取消（终态）。 */
    public TaskRecord cancel(String taskId) {
        TaskRecord task = require(taskId);
        TaskRecord cancelled = save(task.with(TaskState.CANCELLED, task.step(), task.attempts(),
                "cancelled by operator"));
        event(cancelled.sessionId(), cancelled, "cancelled");
        return cancelled;
    }

    /**
     * 启动时认领孤儿：把仍然写着 {@code RUNNING} 的任务标成 {@code CRASHED}。
     *
     * <p>凭什么认定它们死了？因为我们**刚刚启动**——如果它们还在跑，那执行者是另一个进程，
     * 而那个进程的存活不在我们的掌握里。所以这里的假设是明确的：**单实例**。
     *
     * <p>多实例部署时这条就不成立（B 实例会把 A 实例正在跑的任务标成崩溃，然后抢着重跑）。
     * 那需要"执行者 id + 心跳租约"：超时未续租才算孤儿。它属于 U33（多租户/集群）的议题，
     * 现在刻意不做——**一个错的兜底比没有兜底更危险**。
     *
     * @return 被认领的条数
     */
    public int reclaimOrphans() {
        int reclaimed = 0;
        for (TaskRecord task : list()) {
            if (task.state() != TaskState.RUNNING) {
                continue;
            }
            TaskRecord crashed = save(task.with(TaskState.CRASHED, checkpointStep(task), task.attempts(),
                    "reclaimed on startup: the process that was running it is gone"));
            event(crashed.sessionId(), crashed, "crashed");
            reclaimed++;
        }
        return reclaimed;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 任务"跑到第几步"**从检查点读**，不在任务记录里另存一份。
     *
     * <p>起初我在每次 step 末尾顺手把步数写回任务记录，后来改掉了：那样有两个真相来源，
     * 而它们会在**检查点写成功、任务记录写失败**时悄悄分叉——
     * 于是"任务说跑到第 3 步，但检查点在第 2 步"，续跑接错位置，且事后无从判断哪个对。
     *
     * <p>检查点本身就是"上下文被保存到哪一步"的权威记录，问它即可。
     * {@link TaskRecord#step()} 于是只有两个写入时机：认领孤儿时（读检查点）与收口时（读实际步数）。
     */
    private int checkpointStep(TaskRecord task) {
        return checkpointer.latest(task.taskId())
                .map(Checkpointer.Checkpoint::step)
                .orElse(task.step());
    }

    private void guardRunnable(TaskRecord task, String what) {
        if (task.state().terminal()) {
            throw new IllegalStateException("task " + task.taskId() + " is " + task.state()
                    + " — cannot " + what + " a terminal task");
        }
    }

    private TaskRecord finish(TaskRecord running, TurnResult result, boolean resumed) {
        TaskState state = switch (result.status()) {
            case COMPLETED -> TaskState.SUCCEEDED;
            // 步数用尽与内部错误都算 FAILED：重跑会得到同样的结果，所以是终态。
            // 真正"没跑完"的情形（进程被杀）走的是 CRASHED，那条路才允许续跑。
            case MAX_STEPS, ERROR -> TaskState.FAILED;
        };
        String detail = switch (result.status()) {
            case COMPLETED -> abbreviate(result.finalText());
            case MAX_STEPS -> "step budget exhausted";
            case ERROR -> "loop error";
        };
        TaskRecord done = save(running.with(state, result.steps(), running.attempts(),
                (resumed ? "[resumed] " : "") + detail));
        event(done.sessionId(), done, state.name().toLowerCase());
        return done;
    }

    private TaskRecord save(TaskRecord task) {
        store.put(NS, task.taskId(), task.toJson());
        return task;
    }

    private void event(String sessionId, TaskRecord task, String phase) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", task.taskId());
        payload.put("state", task.state().name());
        payload.put("step", task.step());
        payload.put("attempts", task.attempts());
        payload.put("phase", phase);
        loop.sessionLog().append(sessionId, SessionLog.EV_TASK_STATE, payload);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
