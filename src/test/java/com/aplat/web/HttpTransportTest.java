package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Sandbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U02 的对外验收：**一句话进去、逐 token 出来**。
 *
 * <p>用真的 HTTP 服务（端口 0 由系统分配）+ 脚本化模型，所以这里既不要网络也不要模型 key，
 * 但走的是和生产完全一样的传输路径。
 */
class HttpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    // ------------------------------------------------------------------ 装配

    private static ScriptedLlmAdapter scripted() {
        return new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"echo hello-from-http\"}")
                .thenText("已在沙箱中执行命令，输出为 hello-from-http。");
    }

    private HttpTransport start(LlmAdapter llm, ServerConfig config) throws IOException {
        Sandbox sandbox = new ProcessSandbox();
        Platform platform = Platform.assemble(llm, sandbox, Hitl.autoAllow(), LoopBudget.defaults());
        transport = new HttpTransport(platform, config.withPort(0)).start();
        return transport;
    }

    private HttpTransport startDefault() throws IOException {
        return start(scripted(), ServerConfig.defaults().withHeartbeatMillis(500));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(transport.baseUrl() + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(transport.baseUrl() + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("服务能起在随机端口上，健康检查如实反映装配")
    void healthReportsAssembly() throws Exception {
        startDefault();

        HttpResponse<String> resp = get("/health");
        JsonNode body = MAPPER.readTree(resp.body());

        assertEquals(200, resp.statusCode());
        assertEquals("ok", body.get("status").asText());
        assertEquals("llm.scripted", body.get("llm").asText());
        assertTrue(body.get("seams").asInt() >= 7, "阶段一的 7 条能力缝都应已装配");
        assertTrue(body.get("auth").asText().contains("disabled"));
    }

    @Test
    @DisplayName("POST /run：跑完一个完整 turn（模型 → 工具 → 沙箱 → 收口）并返回结构化结果")
    void runReturnsTurnSummary() throws Exception {
        startDefault();

        HttpResponse<String> resp = post("/run", "{\"input\":\"帮我跑一条命令\",\"sessionId\":\"s-run\"}");
        JsonNode body = MAPPER.readTree(resp.body());

        assertEquals(200, resp.statusCode());
        assertEquals("COMPLETED", body.get("status").asText());
        assertEquals("s-run", body.get("sessionId").asText());
        assertEquals(2, body.get("steps").asInt());
        assertTrue(body.get("finalText").asText().contains("hello-from-http"));
        assertTrue(body.get("events").asInt() > 0);
    }

    @Test
    @DisplayName("会话 id 缺省时自动生成——调用方不该被强制先建会话")
    void runGeneratesSessionIdWhenAbsent() throws Exception {
        startDefault();

        JsonNode body = MAPPER.readTree(post("/run", "{\"input\":\"你好\"}").body());

        assertTrue(body.get("sessionId").asText().startsWith("s-"));
    }

    @Test
    @DisplayName("参数校验：缺 input / 非法 JSON 都返回 4xx 且带错误码，而不是 500")
    void invalidRequestsAreRejectedCleanly() throws Exception {
        startDefault();

        HttpResponse<String> noInput = post("/run", "{}");
        assertEquals(400, noInput.statusCode());
        assertEquals("MISSING_INPUT", MAPPER.readTree(noInput.body()).get("error").asText());

        HttpResponse<String> badJson = post("/run", "not-json");
        assertEquals(400, badJson.statusCode());
        assertEquals("INVALID_JSON", MAPPER.readTree(badJson.body()).get("error").asText());
    }

    @Test
    @DisplayName("GET /agui/stream：AG-UI 事件按发生顺序逐条推送，最后自收口")
    void streamEmitsAgUiEventSequence() throws Exception {
        startDefault();

        List<SseTestClient.Frame> frames = SseTestClient.collect(
                transport.baseUrl() + "/agui/stream?input=" + enc("帮我跑一条命令") + "&sessionId=s-stream",
                Duration.ofSeconds(20));

        List<String> events = frames.stream().map(SseTestClient.Frame::event).toList();
        // 这份序列就是"前端可依赖的事件序列"（U08 的契约）。CUSTOM_* 是本平台自有事件，
        // AG-UI 允许未知事件走 CUSTOM 前缀，前端可以安全忽略。
        assertEquals(List.of(
                "RUN_REQUESTED",          // 传输层回执
                "CUSTOM_INPUT_CLAIMED",   // input.claimed
                "RUN_STARTED",            // turn.started
                "CUSTOM_CONTEXT_PREPARED",// 压缩决策留痕
                "STEP_STARTED",           // step 1
                "TOOL_CALL_START",        // tool.call
                "TOOL_CALL_END",          // tool.result
                "STATE_SNAPSHOT",         // 每步快照
                "CUSTOM_CONTEXT_PREPARED",
                "STEP_STARTED",           // step 2
                "TEXT_MESSAGE_CONTENT",   // llm.chunk
                "RUN_FINISHED",           // turn.closed
                "RUN_RESULT"              // 传输层回执
        ), events);

        // 逐 token：文本是以 chunk 形式来的，而且 seq 单调递增（前端续传的前提）
        SseTestClient.Frame text = frames.stream()
                .filter(f -> "TEXT_MESSAGE_CONTENT".equals(f.event())).findFirst().orElseThrow();
        assertTrue(text.dataContains("hello-from-http"));

        List<Long> seqs = frames.stream()
                .map(SseTestClient.Frame::id).filter(java.util.Objects::nonNull).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1), "seq 必须严格递增，否则 Last-Event-ID 无法定位");
        }
    }

    @Test
    @DisplayName("工具调用事件带完整参数与结果——前端不查后端就能画出过程")
    void toolFramesCarryArgumentsAndResult() throws Exception {
        startDefault();

        List<SseTestClient.Frame> frames = SseTestClient.collect(
                transport.baseUrl() + "/agui/stream?input=跑命令&sessionId=s-tool",
                Duration.ofSeconds(20));

        SseTestClient.Frame start = frames.stream()
                .filter(f -> "TOOL_CALL_START".equals(f.event())).findFirst().orElseThrow();
        assertTrue(start.dataContains("\"shell\""));
        assertTrue(start.dataContains("hello-from-http"), "调用的参数要能看到");

        SseTestClient.Frame end = frames.stream()
                .filter(f -> "TOOL_CALL_END".equals(f.event())).findFirst().orElseThrow();
        assertTrue(end.dataContains("\"ok\":true"));
        assertTrue(end.dataContains("hello-from-http"), "执行结果要能看到");
    }

    @Test
    @DisplayName("流式缺 input 参数返回 400，不占用连接")
    void streamRequiresInput() throws Exception {
        startDefault();

        HttpResponse<String> resp = get("/agui/stream");
        assertEquals(400, resp.statusCode());
        assertEquals("MISSING_INPUT", MAPPER.readTree(resp.body()).get("error").asText());
    }

    @Test
    @DisplayName("未知路由 404，且错误体是结构化 JSON")
    void unknownRouteIsStructured404() throws Exception {
        startDefault();

        HttpResponse<String> resp = get("/nope");
        assertEquals(404, resp.statusCode());
        assertEquals("NOT_FOUND", MAPPER.readTree(resp.body()).get("error").asText());
    }

    @Test
    @DisplayName("内置调试控制台可访问（用来肉眼确认链路，不是 U07 正式前端）")
    void consoleIsServed() throws Exception {
        startDefault();

        HttpResponse<String> resp = get("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(resp.body().contains("EventSource"), "控制台必须真的用 SSE 而不是假装");
    }

    @Test
    @DisplayName("配了 APLAT_API_KEY 就必须带对：/health 豁免，业务面 401")
    void apiKeyIsEnforcedWhenConfigured() throws Exception {
        start(new ScriptedLlmAdapter().thenText("hi"),
                ServerConfig.defaults().withPort(0).withApiKey("dev-secret"));

        // 健康检查豁免——探针不该需要凭据
        assertEquals(200, get("/health").statusCode());

        HttpResponse<String> denied = post("/run", "{\"input\":\"hi\"}");
        assertEquals(401, denied.statusCode());
        assertEquals("UNAUTHORIZED", MAPPER.readTree(denied.body()).get("error").asText());

        HttpResponse<String> badKey = CLIENT.send(
                HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/run"))
                        .header("Content-Type", "application/json")
                        .header("X-API-Key", "wrong")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(401, badKey.statusCode());

        HttpResponse<String> allowed = CLIENT.send(
                HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/run"))
                        .header("Content-Type", "application/json")
                        .header("X-API-Key", "dev-secret")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, allowed.statusCode());
    }

    @Test
    @DisplayName("CORS 头在开发态可用——否则前端从 3000 端口连不上")
    void corsHeadersArePresent() throws Exception {
        startDefault();

        HttpResponse<String> resp = get("/health");
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    @DisplayName("/kernel 暴露装配清单，排查时先看这一页")
    void kernelReportListsSeams() throws Exception {
        startDefault();

        JsonNode body = MAPPER.readTree(get("/kernel").body());
        assertNotNull(body.get("assembly"));
        assertTrue(body.get("assembly").has("LlmAdapter"));
        assertTrue(body.get("assembly").has("SessionLog"));
        assertFalse(body.get("replaceable").isEmpty());
    }

    @Test
    @DisplayName("处理链内部异常返回 500 结构化错误，而不是把连接悄悄掐掉")
    void internalErrorYieldsStructured500() throws Exception {
        // 让 /health 里的 llm.id() 炸掉，模拟"某个能力缝实现有 bug"
        LlmAdapter broken = new LlmAdapter() {
            @Override
            public String id() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void stream(com.aplat.seam.LlmRequest request,
                               java.util.function.Consumer<com.aplat.seam.LlmChunk> sink) {
                // 不会走到这里
            }
        };
        start(broken, ServerConfig.defaults().withPort(0));

        HttpResponse<String> resp = get("/health");

        assertEquals(500, resp.statusCode(),
                "只记日志不回应答，客户端会看到'连接被重置'，排查时会误判成网络问题");
        assertEquals("INTERNAL", MAPPER.readTree(resp.body()).get("error").asText());
        assertTrue(resp.body().contains("boom"));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
