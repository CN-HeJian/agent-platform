package com.aplat.hitl;

import java.time.Instant;

/**
 * 一条正在等人回答的确认请求（对外可见的那一面）。
 *
 * <p>它是 {@link com.aplat.seam.HitlRequest} 的"排队态"：多了 {@code requestId}（回答时要引用）
 * 与 {@code expiresAt}（人不在的话等到什么时候）。
 */
public record PendingApproval(
        String requestId,
        String sessionId,
        String toolName,
        String argumentsJson,
        String reason,
        Instant requestedAt,
        Instant expiresAt) {

    /** 剩余等待时间。已经过期返回 0（不是负数——调用方不该去做这个减法）。 */
    public long remainingMillis() {
        return Math.max(0L, expiresAt.toEpochMilli() - System.currentTimeMillis());
    }
}
