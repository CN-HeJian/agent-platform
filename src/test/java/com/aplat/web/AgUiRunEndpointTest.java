package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmMessage;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U08 + U07a 的接口验收：{@code POST /agui/run} 真的能喂给 AG-UI 客户端。
 *
 * <p>用 curl 手测过 AG-UI 是不够的——这里断言的是**规范字段**：{@code threadId/runId}
 * 在 RUN_STARTED 里、{@code messageId} 贯穿一条消息、{@code toolCallId} 把
 * 工具四件套串起来。这些字段缺一个，CopilotKit 就渲染不出来。
 */
class AgUiRunEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private HttpTransport transport;
    private ScriptedLlmAdapter llm;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    private void start() {
        llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"echo agui\"}")
                .thenText("已在沙箱中执行，输出为 agui。");
        Platform platform = Platform.assemble(llm, new ProcessSandbox(), Hitl.autoAllow(), LoopBudget.defaults());
        try {
            transport = new HttpTransport(platform, ServerConfig.defaults().withPort(0)).start();
        } catch (IOException e) {
            // 起不来就是测试环境问题，没必要让每个用例都声明受检异常
            throw new UncheckedIOException(e);
        }
    }

    private List<SseTestClient.Frame> run(String body) {
        return SseTestClient.collectPost(transport.baseUrl() + "/agui/run", body, Duration.ofSeconds(20));
    }

    private static String runBody(String threadId, String... userAssistantPairs) {
        StringBuilder messages = new StringBuilder();
        for (String text : userAssistantPairs) {
            if (messages.length() > 0) {
                messages.append(',');
            }
            messages.append("{\"id\":\"m\",").append("\"role\":\"user\",\"content\":\"").append(text).append("\"}");
        }
        return "{\"threadId\":\"" + threadId + "\",\"runId\":\"r-1\",\"messages\":[" + messages + "]}";
    }

    private static List<String> typesOf(List<SseTestClient.Frame> frames) {
        List<String> out = new ArrayList<>();
        for (SseTestClient.Frame f : frames) {
            out.add(f.event());
        }
        return out;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("完整事件序列符合 AG-UI 规范（这就是前端的契约）")
    void emitsSpecCompliantSequence() {
        start();

        List<SseTestClient.Frame> frames = run(runBody("t-1", "帮我跑一条命令"));

        assertEquals(List.of(
                "RUN_STARTED",
                "STEP_STARTED",
                "TOOL_CALL_START",
                "TOOL_CALL_ARGS",
                "TOOL_CALL_END",
                "TOOL_CALL_RESULT",
                "STATE_SNAPSHOT",
                "STEP_FINISHED",
                "STEP_STARTED",
                "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END",
                "STEP_FINISHED",
                "RUN_FINISHED"), typesOf(frames));
    }

    @Test
    @DisplayName("事件流对客户端是「可接受」的：每个 STEP_STARTED 都有配对，RUN_FINISHED 最后才发")
    void streamSatisfiesClientLifecycleChecks() {
        start();

        List<String> types = typesOf(run(runBody("t-lifecycle", "跑命令")));

        int started = 0;
        int finished = 0;
        for (String t : types) {
            switch (t) {
                case "STEP_STARTED" -> started++;
                case "STEP_FINISHED" -> finished++;
                default -> {
                }
            }
        }
        assertEquals(started, finished, "step 生命周期必须配平，实际序列: " + types);
        assertTrue(types.indexOf("RUN_FINISHED") > types.lastIndexOf("STEP_FINISHED"),
                "RUN_FINISHED 必须等在所有 step 关完之后: " + types);
        assertEquals(1, types.stream().filter("RUN_FINISHED"::equals).count());
    }

    @Test
    @DisplayName("规范字段逐个到位：threadId / runId / messageId / toolCallId")
    void carriesRequiredSpecFields() throws Exception {
        start();

        List<SseTestClient.Frame> frames = run(runBody("t-fields", "跑命令"));

        JsonNode started = MAPPER.readTree(frame(frames, "RUN_STARTED").data());
        assertEquals("t-fields", started.get("threadId").asText());
        assertEquals("r-1", started.get("runId").asText());

        JsonNode toolStart = MAPPER.readTree(frame(frames, "TOOL_CALL_START").data());
        assertNotNull(toolStart.get("toolCallId"));
        assertEquals("shell", toolStart.get("toolCallName").asText());

        JsonNode args = MAPPER.readTree(frame(frames, "TOOL_CALL_ARGS").data());
        assertEquals(toolStart.get("toolCallId").asText(), args.get("toolCallId").asText());
        assertTrue(args.get("delta").asText().contains("echo agui"));

        JsonNode textStart = MAPPER.readTree(frame(frames, "TEXT_MESSAGE_START").data());
        JsonNode textContent = MAPPER.readTree(frame(frames, "TEXT_MESSAGE_CONTENT").data());
        JsonNode textEnd = MAPPER.readTree(frame(frames, "TEXT_MESSAGE_END").data());
        String messageId = textStart.get("messageId").asText();
        assertEquals(messageId, textContent.get("messageId").asText());
        assertEquals(messageId, textEnd.get("messageId").asText());
        assertEquals("assistant", textStart.get("role").asText());

        JsonNode finished = MAPPER.readTree(frame(frames, "RUN_FINISHED").data());
        assertEquals("t-fields", finished.get("threadId").asText());
    }

    @Test
    @DisplayName("id 唯一且递增——同一条内部事件产出多条 AG-UI 事件时也不会撞号")
    void frameIdsAreUniqueAndMonotonic() {
        start();

        List<SseTestClient.Frame> frames = run(runBody("t-ids", "跑命令"));

        List<Long> ids = new ArrayList<>();
        for (SseTestClient.Frame f : frames) {
            if (f.id() != null) {
                ids.add(f.id());
            }
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        assertEquals(ids.size(), unique.size(), "id 撞号会让 Last-Event-ID 断点定位错位: " + ids);
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1), "id 必须严格递增: " + ids);
        }
    }

    @Test
    @DisplayName("多轮对话：历史被转成 LlmMessage 喂给循环，模型不再失忆")
    void historyIsPassedToTheLoop() {
        start();

        List<SseTestClient.Frame> frames = SseTestClient.collectPost(
                transport.baseUrl() + "/agui/run",
                """
                {"threadId":"t-hist","runId":"r-1","messages":[
                  {"role":"user","content":"我叫小王"},
                  {"role":"assistant","content":"你好，小王"},
                  {"role":"user","content":"我叫什么"}]}""",
                Duration.ofSeconds(20));

        assertTrue(typesOf(frames).contains("RUN_FINISHED"), "这次 run 应该正常跑完");

        List<LlmMessage> firstRequest = llm.requests().get(0).messages();
        assertEquals(4, firstRequest.size(),
                "应为 system + 历史 2 条 + 本轮输入，实际: " + firstRequest);
        assertEquals("system", firstRequest.get(0).role());
        assertEquals("user", firstRequest.get(1).role());
        assertEquals("我叫小王", firstRequest.get(1).content());
        assertEquals("assistant", firstRequest.get(2).role(), "assistant 轮次必须保留，否则上下文断裂");
        assertEquals("我叫什么", firstRequest.get(3).content());
    }

    @Test
    @DisplayName("只取最新一条 user 输入：历史里的旧问题不会被执行第二遍")
    void onlyLatestUserMessageIsExecuted() {
        start();

        run("""
            {"threadId":"t-last","runId":"r-1","messages":[
              {"role":"user","content":"第一条"},
              {"role":"user","content":"第二条"}]}""");

        List<LlmMessage> messages = llm.requests().get(0).messages();
        assertEquals("第二条", messages.get(messages.size() - 1).content());
        assertEquals(3, messages.size(), "system + 历史 1 条 + 本轮 1 条: " + messages);
    }

    @Test
    @DisplayName("content 支持 AG-UI 的分片数组形式")
    void supportsContentParts() {
        start();

        run("""
            {"threadId":"t-parts","runId":"r-1","messages":[
              {"role":"user","content":[{"type":"text","text":"分片"},{"type":"text","text":"输入"}]}]}""");

        List<LlmMessage> messages = llm.requests().get(0).messages();
        assertEquals("分片输入", messages.get(messages.size() - 1).content());
    }

    @Test
    @DisplayName("缺 threadId/runId 也能跑（本地 curl 手测不该被迫先造 id）")
    void generatesIdsWhenAbsent() throws Exception {
        start();

        List<SseTestClient.Frame> frames = run("{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}");

        JsonNode started = MAPPER.readTree(frame(frames, "RUN_STARTED").data());
        assertTrue(started.get("threadId").asText().startsWith("thread-"));
        assertTrue(started.get("runId").asText().startsWith("run-"));
    }

    @Test
    @DisplayName("没有 user 消息 → 400，且不占用连接")
    void requiresAUserMessage() throws Exception {
        start();

        HttpResponse<String> resp = CLIENT.send(
                HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/agui/run"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"messages\":[{\"role\":\"assistant\",\"content\":\"hi\"}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(400, resp.statusCode());
        assertEquals("MISSING_INPUT", MAPPER.readTree(resp.body()).get("error").asText());
    }

    @Test
    @DisplayName("threadId 就是 sessionId：跑完之后能按 thread 回放事件")
    void threadIdMapsToSessionId() throws Exception {
        start();

        run(runBody("t-session", "跑命令"));

        JsonNode replay = MAPPER.readTree(CLIENT.send(
                        HttpRequest.newBuilder(URI.create(
                                        transport.baseUrl() + "/sessions/t-session/events")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .body());
        assertTrue(replay.get("count").asInt() > 0, "thread 与 session 必须是同一个身份");
    }

    private static SseTestClient.Frame frame(List<SseTestClient.Frame> frames, String type) {
        return frames.stream()
                .filter(f -> type.equals(f.event()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少事件 " + type + "，实际有 " + typesOf(frames)));
    }
}
