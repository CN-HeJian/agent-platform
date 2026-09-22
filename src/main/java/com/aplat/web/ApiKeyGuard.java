package com.aplat.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 最小 API Key 守卫。
 *
 * <p><b>定位</b>：这是 U16（鉴权）的<b>前置安全网</b>，不是 U16 本身。它只做一件事——
 * "配了 key 就必须带对，否则 401"。RBAC、按角色授权、密钥轮转、审计与限流都在 U16/U17/U25。
 *
 * <p>为什么要现在就有：这个服务能执行 shell。默认只听 127.0.0.1，但只要有人把它暴露出去，
 * 没有这道闸就是"任意命令执行即服务"。所以宁可提前放一个 30 行的闸，也不要留一个裸奔窗口。
 *
 * <p>支持的携带方式（任选其一）：
 * <pre>
 *   X-API-Key: &lt;key&gt;
 *   Authorization: Bearer &lt;key&gt;
 *   ?apiKey=&lt;key&gt;        （只给 EventSource 用——它不能自定义请求头）
 * </pre>
 */
public final class ApiKeyGuard {

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_AUTH = "Authorization";
    private static final String BEARER = "Bearer ";

    private final String expected;

    public ApiKeyGuard(String expectedKey) {
        this.expected = expectedKey == null || expectedKey.isBlank() ? null : expectedKey;
    }

    public boolean enabled() {
        return expected != null;
    }

    /**
     * @param headerLookup 大小写不敏感的取头函数
     * @param queryApiKey  查询串里的 apiKey（EventSource 无法带头，必须留这条路）
     */
    public boolean allowed(java.util.function.Function<String, String> headerLookup, String queryApiKey) {
        if (!enabled()) {
            return true; // 未配置 = 不鉴权（仅本机自查场景）
        }
        Optional<String> presented = firstNonBlank(
                headerLookup.apply(HEADER_API_KEY),
                bearerOf(headerLookup.apply(HEADER_AUTH)),
                queryApiKey);
        return presented.map(this::matches).orElse(false);
    }

    /** 常量时间比较，避免把 key 逐字符比出时间差。 */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String bearerOf(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return null;
        }
        return authorizationHeader.substring(BEARER.length()).trim();
    }

    /** 注意不能用 List.of(...)：它拒绝 null 元素，而"没带这个头"恰恰就是 null。 */
    private static Optional<String> firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
