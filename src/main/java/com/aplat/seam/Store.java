package com.aplat.seam;

import java.util.List;
import java.util.Optional;

/**
 * 持久化缝。阶段一用 MySQL；开发/单测用内存实现顶替，保证"起服务不用先起数据库"。
 *
 * <p>注意它**不是**会话日志本身：日志是"追加 + 投影"的语义，Store 只管落盘与幂等。
 */
public interface Store extends Seam {

    /** 落盘一条事件并返回分配的 seq。 */
    long append(SessionEvent event);

    List<SessionEvent> events(String sessionId, long afterSeq);

    void saveSnapshot(Snapshot snapshot);

    Optional<Snapshot> latestSnapshot(String sessionId);

    /** 幂等：首次见到返回 true，重复返回 false（执行前查、执行后标）。 */
    boolean markIfAbsent(String idempotencyKey, String ref);

    Optional<String> idempotentRef(String idempotencyKey);

    record Snapshot(String sessionId, String stepId, String stateJson, java.time.Instant ts) {
    }
}
