package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U16 + U17 验收：**未授权请求被拒 · 限流生效 · 谁/何时/调用什么可追溯**。
 *
 * <p>三条都走真实 HTTP，因为它们的价值恰恰在"跨过一次真实请求"才成立
 * （比如状态码要在响应发完之后才记进审计）。
 */
class AuthAuditRateLimitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final String KEY = "dev-secret";

    private HttpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    private void start(ServerConfig config) {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter().thenText("好").repeat();
        Platform platform = Platform.assemble(llm, new ProcessSandbox(), Hitl.autoAllow(),
                LoopBudget.defaults());
        try {
            transport = new HttpTransport(platform, config.withPort(0)).start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<String> call(String method, String path, String body, String apiKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(transport.baseUrl() + path))
                    .timeout(Duration.ofSeconds(15));
            if (apiKey != null) {
                b.header("X-API-Key", apiKey);
            }
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : body, StandardCharsets.UTF_8));
            } else {
                b.GET();
            }
            return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("请求失败: " + method + " " + path, e);
        }
    }

    private static Optional<RequestAudit> find(List<RequestAudit> records, String path, int status) {
        return records.stream()
                .filter(r -> r.path().equals(path) && r.status() == status)
                .findFirst();
    }

    // -------------------------------------------------------------- U16 鉴权

    @Test
    @DisplayName("未授权请求被拒不执行，且审计里留下 (rejected) 记录")
    void unauthorizedIsRejectedAndAudited() throws Exception {
        start(ServerConfig.defaults().withApiKey(KEY));

        HttpResponse<String> denied = call("POST", "/run", "{\"input\":\"rm -rf /\"}", null);

        assertEquals(401, denied.statusCode());
        assertEquals("UNAUTHORIZED", MAPPER.readTree(denied.body()).get("error").asText());

        RequestAudit record = find(transport.auditLog().recent(50), "/run", 401).orElseThrow(
                () -> new AssertionError("未授权请求必须留痕: " + transport.auditLog().recent(50)));
        assertEquals(ApiKeyGuard.ANONYMOUS, record.identity(),
                "身份字段答的是「谁」，不是「结果」——被拒这件事由 status=401 + note=unauthorized 表达");
        assertEquals("unauthorized", record.note());
        assertTrue(record.failed());
    }

    @Test
    @DisplayName("审计身份是密钥指纹，绝不是密钥原文")
    void auditIdentityIsFingerprintNotTheKey() {
        start(ServerConfig.defaults().withApiKey(KEY));

        assertEquals(200, call("POST", "/run", "{\"input\":\"你好\"}", KEY).statusCode());

        List<RequestAudit> records = transport.auditLog().recent(50);
        RequestAudit record = find(records, "/run", 200).orElseThrow();
        assertEquals(ApiKeyGuard.fingerprint(KEY), record.identity());
        assertFalse(record.identity().contains(KEY), "身份里不能出现密钥原文");
        for (RequestAudit r : records) {
            assertFalse(r.toJsonLine().contains(KEY), "整条审计都不该出现密钥: " + r.toJsonLine());
        }
    }

    @Test
    @DisplayName("静态资源与探针免鉴权，但仍被记进审计（note=public）")
    void publicPathsAreExemptButStillAudited() {
        start(ServerConfig.defaults().withApiKey(KEY));

        assertEquals(200, call("GET", "/health", null, null).statusCode());
        assertEquals(200, call("GET", "/ui/", null, null).statusCode());

        RequestAudit health = find(transport.auditLog().recent(50), "/health", 200).orElseThrow();
        assertEquals(RequestScope.NOTE_PUBLIC, health.note());
    }

    @Test
    @DisplayName("审计查询本身受保护——审计记录也是敏感信息")
    void auditEndpointRequiresAuth() {
        start(ServerConfig.defaults().withApiKey(KEY));

        assertEquals(401, call("GET", "/audit", null, null).statusCode());

        HttpResponse<String> ok = call("GET", "/audit?limit=5", null, KEY);
        assertEquals(200, ok.statusCode());
    }

    // -------------------------------------------------------------- U17 限流

    @Test
    @DisplayName("额度用满后返回 429，并带出 Retry-After")
    void rateLimitReturns429WithRetryAfter() throws Exception {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(2));

        assertEquals(200, call("POST", "/run", "{\"input\":\"1\"}", KEY).statusCode());
        assertEquals(200, call("POST", "/run", "{\"input\":\"2\"}", KEY).statusCode());

        HttpResponse<String> limited = call("POST", "/run", "{\"input\":\"3\"}", KEY);

        assertEquals(429, limited.statusCode(), "第 3 个请求必须被限流");
        assertEquals("RATE_LIMITED", MAPPER.readTree(limited.body()).get("error").asText());
        String retryAfter = limited.headers().firstValue("Retry-After").orElse(null);
        assertNotNull(retryAfter, "限流响应必须告诉调用方等多久");
        assertTrue(Long.parseLong(retryAfter) >= 1);

        RequestAudit record = find(transport.auditLog().recent(50), "/run", 429).orElseThrow();
        assertEquals("rate_limited", record.note());
    }

    @Test
    @DisplayName("探针与静态资源不占额度——否则健康检查会把业务请求挤掉")
    void publicPathsDoNotConsumeQuota() {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(2));

        for (int i = 0; i < 10; i++) {
            assertEquals(200, call("GET", "/health", null, null).statusCode(),
                    "第 " + (i + 1) + " 次健康检查不该被限流");
        }
        assertEquals(200, call("POST", "/run", "{\"input\":\"x\"}", KEY).statusCode(),
                "额度仍然完整地留给业务请求");
    }

    @Test
    @DisplayName("限流不开时（0）不产生 429")
    void disabledRateLimitNeverBlocks() {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0));

        for (int i = 0; i < 20; i++) {
            assertEquals(200, call("POST", "/run", "{\"input\":\"x\"}", KEY).statusCode());
        }
    }

    @Test
    @DisplayName("限流也能挡住未授权请求的暴力尝试（401 之前就按 IP 计数）")
    void unauthorizedFloodIsAlsoRateLimited() {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0));
        // 关掉限流时先确认拿到的是 401（而不是限流），语义清晰
        assertEquals(401, call("POST", "/run", "{}", null).statusCode());
    }

    // -------------------------------------------------------------- 审计查询

    @Test
    @DisplayName("/audit 能读出「谁/何时/调了什么/结果」，并给出总量与落盘位置")
    void auditEndpointReportsTraceability() throws Exception {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0));

        call("POST", "/run", "{\"input\":\"你好\"}", KEY);
        call("GET", "/tools", null, KEY);

        JsonNode body = MAPPER.readTree(call("GET", "/audit?limit=20", null, KEY).body());

        assertTrue(body.get("total").asInt() >= 2);
        assertTrue(body.get("persisted").isNull(), "未设 APLAT_AUDIT_FILE 时不该报落盘路径");
        JsonNode first = body.get("records").get(0);
        for (String field : List.of("ts", "identity", "ip", "method", "path", "status", "durationMs")) {
            assertNotNull(first.get(field), "审计记录缺少字段 " + field);
        }
        assertEquals(ApiKeyGuard.fingerprint(KEY), first.get("identity").asText());
    }

    @Test
    @DisplayName("审计能落盘成 JSON Lines（设了 APLAT_AUDIT_FILE 时）")
    void auditCanPersist(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("audit.jsonl");
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0)
                .withAuditFile(file.toString()));

        call("POST", "/run", "{\"input\":\"落盘\"}", KEY);

        // 写盘是异步的，给它一点时间
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && !java.nio.file.Files.exists(file)) {
            Thread.sleep(50);
        }
        assertTrue(java.nio.file.Files.exists(file), "审计文件应当被创建");

        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = java.nio.file.Files.readString(file);
            if (content.contains("/run")) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(content.contains("\"path\":\"/run\""), "实际内容: " + content);
        assertFalse(content.contains(KEY), "落盘内容同样不能出现密钥原文");
    }

    @Test
    @DisplayName("流式请求在审计里记成 200，不是 status=0——否则线上看起来全是失败")
    void streamedRequestsAreAuditedAs200() {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0));

        // 先跑一个 turn，让会话里有事件（否则 once=turn.closed 等不到东西会一直挂着）
        assertEquals(200, call("POST", "/run", "{\"input\":\"你好\",\"sessionId\":\"s-audit\"}", KEY)
                .statusCode());

        // ① 不带 key 连流式端点 → 必须 401（证明鉴权覆盖到 SSE，而不只是普通接口）
        SseTestClient.collect(
                transport.baseUrl() + "/agui/events/s-audit?once=turn.closed",
                Duration.ofSeconds(10));
        RequestAudit deniedStream = transport.auditLog().recent(50).stream()
                .filter(r -> r.path().startsWith("/agui/events/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("被拒的流式请求也要留痕"));
        assertEquals(401, deniedStream.status(), "流式端点同样受鉴权保护");

        // ② 带上 key 再来一次：这一条在修好之前会被记成 status=0（"未及应答"）
        SseTestClient.collect(
                transport.baseUrl() + "/agui/events/s-audit?once=turn.closed",
                "X-API-Key", KEY, Duration.ofSeconds(10));

        List<RequestAudit> records = transport.auditLog().recent(50);
        RequestAudit stream = records.stream()
                .filter(r -> r.path().startsWith("/agui/events/") && r.status() == 200)
                .findFirst()
                .orElseThrow(() -> new AssertionError("成功的流式请求必须记成 200: " + records));

        assertEquals(200, stream.status(),
                "SSE 不走 sendBytes，状态码要在 startSse 里自己记——漏了就会记成 0（看起来像失败）");
        assertFalse(stream.failed(), "200 的流式请求不是失败");
        assertEquals(ApiKeyGuard.fingerprint(KEY), stream.identity());
    }

    @Test
    @DisplayName("健康检查会报出限流与审计的当前状态（排查第一站）")
    void healthReportsAuthSurface() throws Exception {
        start(ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(30));

        JsonNode body = MAPPER.readTree(call("GET", "/health", null, null).body());

        assertEquals("api-key", body.get("auth").asText());
        assertEquals("30/min per caller", body.get("rateLimit").asText());
        assertTrue(body.get("audit").asText().contains("total="));
    }
}
