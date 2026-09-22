package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U02 · 鉴权闸的纯函数验证。
 *
 * <p>这道闸的定位是"U16 之前的安全网"，所以只验三件事：没配就不拦、配了就必须带对、
 * 三种携带方式都要认（EventSource 不能自定义请求头，查询参数那条路不能少）。
 */
class ApiKeyGuardTest {

    private static java.util.function.Function<String, String> headers(Map<String, String> map) {
        return name -> map.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("未配置 key → 不鉴权（仅本机自查场景）")
    void disabledWhenNotConfigured() {
        ApiKeyGuard guard = new ApiKeyGuard(null);

        assertFalse(guard.enabled());
        assertTrue(guard.allowed(headers(Map.of()), null));
    }

    @Test
    @DisplayName("配置了 key → 不携带一律拒绝")
    void rejectsWhenMissing() {
        ApiKeyGuard guard = new ApiKeyGuard("dev-secret");

        assertTrue(guard.enabled());
        assertFalse(guard.allowed(headers(Map.of()), null));
        assertFalse(guard.allowed(headers(Map.of()), ""));
    }

    @Test
    @DisplayName("三种携带方式都要认：X-API-Key / Bearer / 查询参数")
    void acceptsAllThreeCarriers() {
        ApiKeyGuard guard = new ApiKeyGuard("dev-secret");

        assertTrue(guard.allowed(headers(Map.of("X-API-Key", "dev-secret")), null));
        assertTrue(guard.allowed(headers(Map.of("Authorization", "Bearer dev-secret")), null));
        // EventSource 无法自定义头，这条是浏览器唯一可用的路
        assertTrue(guard.allowed(headers(Map.of()), "dev-secret"));
        // 头名大小写不敏感
        assertTrue(guard.allowed(headers(Map.of("x-api-key", "dev-secret")), null));
    }

    @Test
    @DisplayName("错误 key 与空 Bearer 都拒绝")
    void rejectsWrongKey() {
        ApiKeyGuard guard = new ApiKeyGuard("dev-secret");

        assertFalse(guard.allowed(headers(Map.of("X-API-Key", "dev-secre")), null));
        assertFalse(guard.allowed(headers(Map.of("X-API-Key", "DEV-SECRET")), null));
        assertFalse(guard.allowed(headers(Map.of("Authorization", "Bearer ")), null));
        assertFalse(guard.allowed(headers(Map.of("Authorization", "Basic dev-secret")), null));
    }
}
