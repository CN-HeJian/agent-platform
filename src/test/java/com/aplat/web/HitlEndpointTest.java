package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.hitl.InteractiveHitl;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.SessionLog;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.tools.DefaultToolPolicy;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U12/U13 的传输层验收：**确认请求能被看到、能被回答、答完真的继续跑**。
 *
 * <p>这一层必须走真实 HTTP，因为要验的恰恰是"跨两个连接的人机往返"：
 * 一个连接挂在 {@code POST /run} 上等人，另一个连接去回答。用进程内调用测不出这件事。
 */
class HitlEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String KEY = "dev-secret";

    private HttpTransport transport;
    private Platform platform;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    /** 用一个"会问人"的平台起服务。timeout 给足，免得测试自己把自己超时掉。 */
    private void startInteractive(Duration timeout) {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"echo hitl-ok\"}")
                .thenText("已执行。")
                .repeat();
        platform = Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(),
                log -> new InteractiveHitl(log, InteractiveHitl.Mode.ASK, timeout));
        try {
            transport = new HttpTransport(platform,
                    ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0).withPort(0)).start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 非交互式平台（autoAllow）：用来验证"没有队列时接口怎么答"。 */
    private void startNonInteractive() {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter().thenText("好").repeat();
        platform = Platform.assemble(llm, new ProcessSandbox(), Hitl.autoAllow(), LoopBudget.defaults());
        try {
            transport = new HttpTransport(platform,
                    ServerConfig.defaults().withApiKey(KEY).withRateLimitPerMin(0).withPort(0)).start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<String> call(String method, String path, String body, String apiKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(transport.baseUrl() + path))
                    .timeout(Duration.ofSeconds(20));
            if (apiKey != null) {
                b.header("X-API-Key", apiKey);
            }
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body,
                                StandardCharsets.UTF_8));
            } else {
                b.GET();
            }
            return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("请求失败: " + method + " " + path, e);
        }
    }

    private JsonNode json(HttpResponse<String> resp) {
        try {
            return MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new AssertionError("响应不是 JSON: " + resp.body(), e);
        }
    }

    /** 轮询待办队列，直到出现一条待确认（或超时失败）。用轮询而不是 sleep 猜。 */
    private JsonNode awaitOnePending() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode n = json(call("GET", "/hitl/pending", null, KEY));
            if (n.path("count").asInt() == 1) {
                return n.path("pending").get(0);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("被中断", e);
            }
        }
        throw new AssertionError("10s 内没等到待确认请求");
    }

    // ------------------------------------------------------------------ 验收

    @Test
    @DisplayName("U12 全链路：run 挂住等人 → 队列里看得见 → 回答 once → run 继续跑完并真的执行了工具")
    void approvedRunActuallyExecutes() throws Exception {
        startInteractive(Duration.ofSeconds(30));

        CompletableFuture<HttpResponse<String>> run = CompletableFuture.supplyAsync(
                () -> call("POST", "/run", "{\"input\":\"跑一条命令\",\"sessionId\":\"s-hitl\"}", KEY));

        JsonNode pending = awaitOnePending();
        assertEquals("shell", pending.path("tool").asText());
        assertEquals("s-hitl", pending.path("sessionId").asText());
        assertTrue(pending.path("args").asText().contains("hitl-ok"), pending.toString());
        assertTrue(pending.path("remainingMs").asLong() > 0);
        // 请求必须在会话日志里也留一条——界面刷新后还能重建卡片，靠的就是它
        assertEquals(1, ((EventSourcedSessionLog) platform.sessionLog())
                .ofType("s-hitl", SessionLog.EV_HITL_REQUEST).size());

        String requestId = pending.path("requestId").asText();
        HttpResponse<String> answer = call("POST", "/hitl/" + requestId, "{\"decision\":\"once\"}", KEY);
        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(json(answer).path("accepted").asBoolean());

        HttpResponse<String> result = run.get(20, TimeUnit.SECONDS);
        assertEquals(200, result.statusCode(), result.body());
        assertTrue(result.body().contains("\"status\""), result.body());

        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertEquals("once", log.ofType("s-hitl", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
        // 批准之后工具真的跑了（stdout 回到观察里），而不是"看起来批准了其实没执行"
        assertTrue(log.ofType("s-hitl", SessionLog.EV_TOOL_RESULT).get(0).str("content").contains("hitl-ok"),
                log.replay("s-hitl"));
    }

    @Test
    @DisplayName("拒绝：流水线收到 DENIED，run 仍然正常收口（不是 500，也不是卡死）")
    void denialEndsTurnCleanly() throws Exception {
        startInteractive(Duration.ofSeconds(30));

        CompletableFuture<HttpResponse<String>> run = CompletableFuture.supplyAsync(
                () -> call("POST", "/run", "{\"input\":\"跑一条命令\",\"sessionId\":\"s-deny\"}", KEY));

        String requestId = awaitOnePending().path("requestId").asText();
        assertEquals(200, call("POST", "/hitl/" + requestId,
                "{\"decision\":\"deny\",\"reason\":\"不要动我的机器\"}", KEY).statusCode());

        HttpResponse<String> result = run.get(20, TimeUnit.SECONDS);
        assertEquals(200, result.statusCode(), result.body());
        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertEquals("deny", log.ofType("s-deny", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
        assertEquals("DENIED", log.ofType("s-deny", SessionLog.EV_TOOL_RESULT).get(0).str("errorCode"));
    }

    @Test
    @DisplayName("改参：执行的是改后的命令，且 resolved 事件里记下了这次改动")
    void modifiedArgumentsAreExecuted() throws Exception {
        startInteractive(Duration.ofSeconds(30));

        CompletableFuture<HttpResponse<String>> run = CompletableFuture.supplyAsync(
                () -> call("POST", "/run", "{\"input\":\"跑一条命令\",\"sessionId\":\"s-mod\"}", KEY));

        String requestId = awaitOnePending().path("requestId").asText();
        assertEquals(200, call("POST", "/hitl/" + requestId,
                "{\"decision\":\"modify\",\"arguments\":\"{\\\"command\\\":\\\"echo changed-by-human\\\"}\"}", KEY)
                .statusCode());

        run.get(20, TimeUnit.SECONDS);
        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertTrue(log.ofType("s-mod", SessionLog.EV_TOOL_RESULT).get(0).str("content")
                .contains("changed-by-human"), log.replay("s-mod"));
    }

    @Test
    @DisplayName("超时：没人回答时 run 以 TIMEOUT 收口，而不是永远挂着")
    void timeoutEndsRunWithoutHanging() throws Exception {
        startInteractive(Duration.ofMillis(300));

        HttpResponse<String> result = call("POST", "/run",
                "{\"input\":\"跑一条命令\",\"sessionId\":\"s-timeout\"}", KEY);

        assertEquals(200, result.statusCode(), result.body());
        var log = (EventSourcedSessionLog) platform.sessionLog();
        assertEquals("timeout", log.ofType("s-timeout", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
        assertEquals("TIMEOUT", log.ofType("s-timeout", SessionLog.EV_TOOL_RESULT).get(0).str("errorCode"),
                "TIMEOUT 与 DENIED 必须分开：一个说「人不在」，一个说「人反对」");
    }

    // ------------------------------------------------------- 回答口本身的规则

    @Test
    @DisplayName("过期/已答/不存在的 requestId → 409（已存在过，只是不再可答）")
    void staleRequestGets409() {
        startInteractive(Duration.ofSeconds(30));

        HttpResponse<String> nope = call("POST", "/hitl/h999", "{\"decision\":\"once\"}", KEY);
        assertEquals(409, nope.statusCode(), nope.body());
        assertEquals("NOT_PENDING", json(nope).path("error").asText());
    }

    @Test
    @DisplayName("参数错得清楚：缺 decision / 未知值 / 改参却没给 arguments 都是 400")
    void badRequestsAreExplained() {
        startInteractive(Duration.ofSeconds(30));

        HttpResponse<String> missing = call("POST", "/hitl/h1", "{}", KEY);
        assertEquals(400, missing.statusCode());
        assertEquals("MISSING_DECISION", json(missing).path("error").asText());

        HttpResponse<String> unknown = call("POST", "/hitl/h1", "{\"decision\":\"maybe\"}", KEY);
        assertEquals(400, unknown.statusCode());
        assertEquals("UNKNOWN_DECISION", json(unknown).path("error").asText());

        HttpResponse<String> noArgs = call("POST", "/hitl/h1", "{\"decision\":\"modify\"}", KEY);
        assertEquals(400, noArgs.statusCode());
        assertEquals("MISSING_ARGUMENTS", json(noArgs).path("error").asText());

        // timeout 不是人能做的决定（那是系统状态），所以它落进"未知值"而不是被接受
        HttpResponse<String> forged = call("POST", "/hitl/h1", "{\"decision\":\"timeout\"}", KEY);
        assertEquals(400, forged.statusCode());
    }

    @Test
    @DisplayName("非交互式装配：队列接口明确说「没有队列」，提交决定返回 501 并告诉你怎么办")
    void nonInteractivePlatformSaysSo() {
        startNonInteractive();

        JsonNode report = json(call("GET", "/hitl/pending", null, KEY));
        assertFalse(report.path("interactive").asBoolean());
        assertEquals("non-interactive", report.path("mode").asText());
        assertEquals(0, report.path("count").asInt());

        HttpResponse<String> submit = call("POST", "/hitl/h1", "{\"decision\":\"once\"}", KEY);
        assertEquals(501, submit.statusCode(), submit.body());
        assertEquals("HITL_NOT_INTERACTIVE", json(submit).path("error").asText());
        assertTrue(submit.body().contains("APLAT_HITL"), "错误信息要告诉人怎么开: " + submit.body());
    }

    @Test
    @DisplayName("确认面也是 API 面：没 key 一律 401（待办里能看出谁在用、在调什么）")
    void approvalSurfaceRequiresAuth() {
        startInteractive(Duration.ofSeconds(30));

        assertEquals(401, call("GET", "/hitl/pending", null, null).statusCode());
        assertEquals(401, call("POST", "/hitl/h1", "{\"decision\":\"once\"}", null).statusCode());
        assertEquals(401, call("POST", "/hitl/h1", "{\"decision\":\"once\"}", "wrong-key").statusCode());
    }

    @Test
    @DisplayName("/health 报出 HITL 当前状态与待办数（排查第一站）")
    void healthReportsHitlState() {
        startInteractive(Duration.ofSeconds(30));
        JsonNode health = json(call("GET", "/health", null, KEY));

        String hitl = health.path("hitl").asText();
        assertTrue(hitl.contains("interactive"), hitl);
        assertTrue(hitl.contains("ask"), hitl);
        assertTrue(hitl.contains("0 pending"), hitl);
    }
}
