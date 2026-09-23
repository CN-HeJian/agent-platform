package com.aplat.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;

/**
 * API Key 守卫（U16）。
 *
 * <p><b>做到哪一步</b>：只做"配了 key 就必须带对，否则 401"。RBAC、按角色授权、密钥轮转属 U25。
 *
 * <p>为什么要现在就有一个：这个服务能执行 shell。默认只听 127.0.0.1，但只要有人把它暴露出去，
 * 没有这道闸就是"任意命令执行即服务"。
 *
 * <p>支持的携带方式（任选其一）：
 * <pre>
 *   X-API-Key: &lt;key&gt;
 *   Authorization: Bearer &lt;key&gt;
 *   ?apiKey=&lt;key&gt;        （只给 EventSource 用——它不能自定义请求头）
 * </pre>
 *
 * <p><b>身份用指纹，不用原值</b>：校验通过后对外只暴露 {@link #fingerprint(String)}
 * ——SHA-256 的前 8 位十六进制。审计与限流拿它做标识，于是日志里**永远不会出现密钥原文**。
 * 这条有测试钉着。
 */
public final class ApiKeyGuard {

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_AUTH = "Authorization";
    private static final String BEARER = "Bearer ";

    /** 未启用鉴权时的身份标识（限流仍要按调用方隔离，所以不能是 null）。 */
    public static final String ANONYMOUS = "anonymous";

    private final String expected;

    public ApiKeyGuard(String expectedKey) {
        this.expected = expectedKey == null || expectedKey.isBlank() ? null : expectedKey;
    }

    public boolean enabled() {
        return expected != null;
    }

    public boolean allowed(Function<String, String> headerLookup, String queryApiKey) {
        return identify(headerLookup, queryApiKey).isPresent();
    }

    /**
     * 校验并给出**调用方身份**（密钥指纹，或 {@link #ANONYMOUS}）。
     *
     * @param headerLookup 大小写不敏感的取头函数
     * @param queryApiKey  查询串里的 apiKey（EventSource 无法带头，必须留这条路）
     * @return empty = 未通过校验
     */
    public Optional<String> identify(Function<String, String> headerLookup, String queryApiKey) {
        if (!enabled()) {
            return Optional.of(ANONYMOUS);
        }
        return firstNonBlank(
                headerLookup.apply(HEADER_API_KEY),
                bearerOf(headerLookup.apply(HEADER_AUTH)),
                queryApiKey)
                .filter(this::matches)
                .map(ApiKeyGuard::fingerprint);
    }

    /** 常量时间比较，避免把 key 逐字符比出时间差。 */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** 密钥指纹：SHA-256 前 8 位十六进制。够区分调用方，又不可反推。 */
    public static String fingerprint(String key) {
        if (key == null) {
            return ANONYMOUS;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (Exception e) {
            // 理论上不会发生（JDK 必带 SHA-256）；真发生了也不能让请求挂掉
            return "unavailable";
        }
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
