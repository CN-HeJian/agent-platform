package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.DefaultToolPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U22 的传输层验收：任务与调度面。
 *
 * <p>走真 HTTP，因为要验的包括**状态码的语义**：终态任务去续跑该是 409 而不是 404，
 * 建调度时给出的警告必须随 201 一起回到调用方手里——那些都是进程内调用测不出来的东西。
 */
class ScheduleEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private HttpTransport transport;
    private Platform platform;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
        if (platform != null) {
            platform.close();
        }
    }

    private void start() throws IOException {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"echo scheduled\"}")
                .thenText("做完了")
                .repeat();
        platform = Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), new InMemoryStore(), log -> Hitl.autoAllow());
        transport = new HttpTransport(platform, ServerConfig.defaults().withPort(0)).start();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(transport.baseUrl() + path))
                .timeout(Duration.ofSeconds(20));
        b = body == null
                ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .header("Content-Type", "application/json");
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return MAPPER.readTree(r.body());
    }

    @Test
    @DisplayName("建调度：201 + 首次触发时刻；提前批准执行类工具时警告跟着响应一起回来")
    void createSchedule() throws Exception {
        start();
        HttpResponse<String> created = send("POST", "/schedules",
                "{\"goal\":\"跑日报\",\"sessionId\":\"s1\",\"trigger\":{\"kind\":\"interval\",\"seconds\":300}}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode n = json(created);
        assertTrue(n.path("nextRunAt").asLong() > System.currentTimeMillis());
        assertEquals("every 300s", n.path("trigger").asText());
        assertTrue(n.path("warnings").isEmpty());

        // 提前批准一个执行类工具：警告必须随 201 抵达，而不是只写在服务端日志里
        HttpResponse<String> risky = send("POST", "/schedules",
                "{\"goal\":\"半夜跑命令\",\"trigger\":{\"kind\":\"daily\",\"hhmm\":\"02:30\"},"
                        + "\"preApprovedTools\":[\"shell\"]}");
        assertEquals(201, risky.statusCode(), risky.body());
        assertEquals(1, json(risky).path("warnings").size(), risky.body());
        assertTrue(json(risky).path("warnings").get(0).asText().contains("执行类"));

        HttpResponse<String> list = send("GET", "/schedules", null);
        assertEquals(200, list.statusCode());
        assertEquals(2, json(list).path("count").asInt());
    }

    @Test
    @DisplayName("触发器写错要在建的时候被拒（400），不能等到凌晨两点才发现")
    void badTriggerIsRejected() throws Exception {
        start();
        assertEquals(400, send("POST", "/schedules",
                "{\"goal\":\"x\",\"trigger\":{\"kind\":\"cron\",\"expr\":\"* * * * *\"}}").statusCode());
        assertEquals(400, send("POST", "/schedules",
                "{\"goal\":\"x\",\"trigger\":{\"kind\":\"interval\",\"seconds\":0}}").statusCode());
        assertEquals(400, send("POST", "/schedules",
                "{\"goal\":\"x\",\"trigger\":{\"kind\":\"daily\",\"hhmm\":\"25:99\"}}").statusCode());
        assertEquals(400, send("POST", "/schedules",
                "{\"trigger\":{\"kind\":\"interval\",\"seconds\":60}}").statusCode(),
                "没有 goal 的调度到点会做一次空跑，必须在建的时候就拦住");
    }

    @Test
    @DisplayName("run-now 同步跑完并回到结果；任务出现在 /tasks 里")
    void runNowAndTasks() throws Exception {
        start();
        send("POST", "/schedules",
                "{\"goal\":\"跑一次\",\"sessionId\":\"s1\",\"trigger\":{\"kind\":\"interval\",\"seconds\":600}}");
        String id = json(send("GET", "/schedules", null)).path("schedules").get(0).path("scheduleId").asText();

        HttpResponse<String> run = send("POST", "/schedules/" + id + "/run-now", null);
        assertEquals(200, run.statusCode(), run.body());
        assertEquals("SUCCEEDED", json(run).path("state").asText());

        JsonNode tasks = json(send("GET", "/tasks", null));
        assertEquals(1, tasks.path("count").asInt());
        assertEquals(1, tasks.path("terminal").size());
        assertEquals("跑一次", tasks.path("terminal").get(0).path("goal").asText(),
                "goal 要原样带回来，否则面板上分不清这条任务是什么");

        // 终态任务去续跑：409（请求没错，是时机不对），不是 404
        String taskId = tasks.path("terminal").get(0).path("taskId").asText();
        HttpResponse<String> resume = send("POST", "/tasks/" + taskId + "/resume", "{}");
        assertEquals(409, resume.statusCode(), resume.body());
        assertEquals("TASK_NOT_ACTIONABLE", json(resume).path("error").asText());
        assertTrue(json(resume).path("message").asText().contains("终态不重跑"),
                "409 的正文要说清为什么不给续跑，否则调用方只会以为服务坏了: " + resume.body());

        // 不存在的任务才是 404
        assertEquals(404, send("POST", "/tasks/t-nope/cancel", "{}").statusCode(),
                "不存在的任务才是 404——与「存在但不可动」区分开，前端才能给出不同提示");
        assertEquals(400, send("POST", "/tasks/" + taskId + "/乱写", "{}").statusCode());
    }

    @Test
    @DisplayName("停用 / 启用 / 删除")
    void toggleAndDelete() throws Exception {
        start();
        send("POST", "/schedules",
                "{\"goal\":\"x\",\"trigger\":{\"kind\":\"interval\",\"seconds\":120}}");
        String id = json(send("GET", "/schedules", null)).path("schedules").get(0).path("scheduleId").asText();

        assertEquals(200, send("POST", "/schedules/" + id + "/disable", null).statusCode());
        assertEquals(false, json(send("GET", "/schedules", null)).path("schedules").get(0)
                .path("enabled").asBoolean());
        assertEquals(true, json(send("POST", "/schedules/" + id + "/enable", null))
                .path("enabled").asBoolean());

        assertEquals(200, send("DELETE", "/schedules/" + id, null).statusCode());
        assertEquals(404, send("DELETE", "/schedules/" + id, null).statusCode());
        assertEquals(404, send("POST", "/schedules/" + id + "/run-now", null).statusCode());
        assertEquals(400, send("POST", "/schedules/" + id + "/什么鬼", null).statusCode());
    }

    @Test
    @DisplayName("/health 里能看到耐久与调度的实际状态，而不是只有一句 ok")
    void healthShowsDurability() throws Exception {
        start();
        JsonNode health = json(send("GET", "/health", null));
        assertTrue(health.has("store"), health.toString());
        assertEquals(0, json(send("GET", "/tasks", null)).path("count").asInt());
        assertFalse(json(send("GET", "/schedules", null)).path("ticking").asBoolean(),
                "装配期不该自动开始轮询——由 Serve 显式 start()");
    }
}
