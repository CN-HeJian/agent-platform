package com.aplat.durable;

import com.aplat.seam.ToolResult;
import java.util.Optional;

/**
 * 副作用幂等闸（U21）。
 *
 * <p>要解决的问题只有一个：**崩在"副作用已经发生"与"检查点已经写下"之间**。
 * 续跑会重放这一步，如果不管，命令就跑了两遍、消息就发了两条。
 *
 * <p>三段式，顺序不能换：
 * <pre>
 *   claim(key)   →  empty  = 从没见过，去执行
 *                  有值   = 见过：里面是**上次的结果**（回放）或 DUPLICATE_SUPPRESSED（结果未知）
 *   执行...
 *   complete(key, result) → 把结果记下来，供下次回放
 * </pre>
 *
 * <h2>为什么"结果未知"时不重跑，而是报错让模型自己判断</h2>
 *
 * <p>三种崩点各自对应不同处置：
 * <table border="1">
 *   <tr><th>崩在哪</th><th>库里有什么</th><th>续跑时</th></tr>
 *   <tr><td>claim 之前</td><td>什么都没有</td><td>正常执行（这次副作用确实没发生过）</td></tr>
 *   <tr><td>claim 与 complete 之间</td><td>有键、无结果</td><td><b>不执行</b>，报 {@code DUPLICATE_SUPPRESSED}</td></tr>
 *   <tr><td>complete 之后</td><td>有键、有结果</td><td>直接回放上次的结果，<b>不执行</b></td></tr>
 * </table>
 *
 * <p>第二行是唯一需要判断的地方。选择"报错"而不是"重跑"，理由很硬：
 * 我们**不知道**那个命令到底跑没跑完（进程是在它执行到一半时没的）。
 * 重跑可能把一次削价变成两次；而报错最多让模型多问一句、让人看一眼。
 * 这类场景下，"少做一次"永远优于"多做一次"。
 */
public interface IdempotencyGuard {

    Optional<ToolResult> claim(String key);

    void complete(String key, ToolResult result);

    /** 结果未知时的错误码：既不是"被拒"，也不是"失败"，而是"可能已经发生过了"。 */
    String ERR_DUPLICATE_SUPPRESSED = "DUPLICATE_SUPPRESSED";

    static IdempotencyGuard none() {
        return new IdempotencyGuard() {
            @Override
            public Optional<ToolResult> claim(String key) {
                return Optional.empty();
            }

            @Override
            public void complete(String key, ToolResult result) {
            }

            @Override
            public String toString() {
                return "idempotency.none";
            }
        };
    }
}
