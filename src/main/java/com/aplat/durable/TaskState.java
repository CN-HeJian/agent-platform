package com.aplat.durable;

/**
 * 耐久任务的七态（U19）。
 *
 * <pre>
 *   PENDING ──接手──> RUNNING ──┬─成功─> SUCCEEDED   （终态）
 *      ▲                        ├─失败─> FAILED      （终态）
 *      │                        ├─人为─> SUSPENDED ──┐
 *      └──────接手──────────────┴─崩溃─> CRASHED  ────┴─> RUNNING
 *                                       CANCELLED   （终态，从任何非终态可达）
 * </pre>
 *
 * <h2>为什么不复用"消息列表里有几条 tool 调用"来推断状态</h2>
 *
 * <p>因为**推断出来的状态无法区分"过程序被杀"与"正在跑"**。这两者从消息上看起来一模一样，
 * 但对系统要求完全不同：前者要能被认领重跑，后者不能被打扰。
 * 状态必须是**写下来的事实**，不是算出来的。
 *
 * <h2>CRASHED 与 FAILED 为什么要分开</h2>
 *
 * <p>它们的处置方式相反：{@code FAILED} 是"跑完了但结果不行"——重跑会得到同样的结果，
 * 所以它是终态；{@code CRASHED} 是"没跑完"——重跑**有意义**，所以它是可续跑的。
 * 把两者合一，要么会让人反复重试一个必然失败的活，要么会让一次崩溃永远无法恢复。
 */
public enum TaskState {

    /** 已受理、还没开始。 */
    PENDING,
    /** 正在跑。注意：进程崩掉时这个状态**留在库里**——它正是"孤儿"的标记。 */
    RUNNING,
    /** 人为挂起，可续跑。 */
    SUSPENDED,
    /** 跑完且结果成功。 */
    SUCCEEDED,
    /** 跑完了但结果不行（例如步数用尽）。重跑会得到同样的结果，所以是终态。 */
    FAILED,
    /** 被取消。 */
    CANCELLED,
    /** 没跑完就没了（进程被杀、机器断电）。**可续跑**。 */
    CRASHED;

    /** 终态：不会再变（除非启动时误判）。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /**
     * 能不能从这个状态继续跑。
     *
     * <p>{@code RUNNING} 也算可续——但那要先经 {@code reclaimOrphans()} 把它改成
     * {@code CRASHED}，因为"一条 RUNNING 的记录被我们读到"本身就说明**它的执行者已经不在了**
     * （否则轮不到我们读）。见 {@link DurableRunner#reclaimOrphans()}。
     */
    public boolean resumable() {
        return this == PENDING || this == RUNNING || this == SUSPENDED || this == CRASHED;
    }
}
