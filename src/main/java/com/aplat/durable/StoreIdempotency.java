package com.aplat.durable;

import com.aplat.seam.Store;
import com.aplat.seam.ToolResult;
import java.util.Optional;

/**
 * 落在 {@link Store} 上的幂等闸（U21）。
 *
 * <p>靠 {@link Store#markIfAbsent} 的**原子首写**做仲裁——不用应用层加锁，
 * 也就不会出现"两个线程都以为自己拿到了执行权"。
 *
 * <p>这里用到 {@link Store#putIdempotentRef} 把"进行中"回填成"结果是这个"。
 * 补上那个方法之前，"崩在副作用之后"这类情形根本没法被识别，
 * 只能盲目重跑一次副作用。
 */
public final class StoreIdempotency implements IdempotencyGuard {

    /** 值用 "@pending" 而不是空串：空串和"结果就是空"没法区分。 */
    static final String IN_FLIGHT = "@pending";

    private static final String PREFIX = "tool:";

    private final Store store;

    public StoreIdempotency(Store store) {
        this.store = store;
    }

    @Override
    public Optional<ToolResult> claim(String key) {
        String idemKey = PREFIX + key;
        if (store.markIfAbsent(idemKey, IN_FLIGHT)) {
            return Optional.empty(); // 第一次，去执行
        }
        String ref = store.idempotentRef(idemKey).orElse(IN_FLIGHT);
        if (IN_FLIGHT.equals(ref)) {
            // 上一次崩在"已声明、未回填"之间：结果未知，绝不重跑
            return Optional.of(ToolResult.error(ERR_DUPLICATE_SUPPRESSED,
                    "this action was already started in an earlier run but never reported back. "
                            + "it may or may not have taken effect — verify before retrying, "
                            + "and prefer an approach that can be checked."));
        }
        Optional<String[]> parsed = DurableCodec.toolResultFromJson(ref);
        if (parsed.isEmpty()) {
            return Optional.of(ToolResult.error(ERR_DUPLICATE_SUPPRESSED,
                    "previous result of this action is unreadable; do not retry blindly."));
        }
        String[] r = parsed.get();
        boolean ok = Boolean.parseBoolean(r[0]);
        // 回放：**不执行**，直接把上次那个结果再交一次，包括失败的
        return Optional.of(ok ? ToolResult.ok(r[1]) : ToolResult.error(r[2], r[1]));
    }

    @Override
    public void complete(String key, ToolResult result) {
        store.putIdempotentRef(PREFIX + key,
                DurableCodec.toolResultToJson(result.ok(), result.content(), result.errorCode()));
    }
}
