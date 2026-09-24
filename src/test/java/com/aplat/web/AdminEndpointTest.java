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
 * U29 验收：运营台。
 *
 * <p>验的关键点是**运营台只该说事实、不该说秘密**：总览里要有"崩掉可续跑"与
 * "待批准"这两个容易安静坏着的数字，而装配视图里**只能出现密钥的变量名，不能出现值**。
 */
class AdminEndpointTest {

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

    private void start() throws Exception {
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall("shell", "{\"command\":\"echo admin\"}")
                .thenText("好了");
        platform = Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), new InMemoryStore(), log -> Hitl.autoAllow());
        transport = new HttpTransport(platform, ServerConfig.defaults().withPort(0)).start();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(transport.baseUrl() + path))
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("/admin/overview：把「现在有什么、什么坏了」放在一个响应里")
    void overview() throws Exception {
        start();
        // 先跑一个 turn，让会话与事件存在
        platform.durable().start(platform.durable().submit("s-admin", "跑一次").taskId());

        HttpResponse<String> r = get("/admin/overview");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = MAPPER.readTree(r.body());
        assertTrue(n.path("sessions").asInt() >= 1, r.body());
        assertTrue(n.path("events").asInt() > 0, r.body());
        assertTrue(n.has("crashedResumable"), "崩掉可续跑的条数必须出现——它是安静坏着的典型");
        assertTrue(n.has("openApprovals"), "待批准条数必须出现——同理");
        assertTrue(n.has("assembly"));

        // 装配视图里只能有变量名，不能有值
        String assembly = n.path("assembly").toString();
        assertTrue(assembly.contains("secretNames"), assembly);
        assertFalse(assembly.matches(".*(sk-|password=)[^\\s\"].*"), assembly);
    }

    @Test
    @DisplayName("/usage：调用与失败按工具分列，还有事件最多的会话")
    void usage() throws Exception {
        start();
        platform.durable().start(platform.durable().submit("s-usage", "跑一次").taskId());

        HttpResponse<String> r = get("/usage");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = MAPPER.readTree(r.body());
        assertTrue(n.path("aplat_tool_calls_total").asInt() >= 1, r.body());
        assertTrue(n.path("aplat_tool_calls_by_tool").has("shell"), r.body());
        assertTrue(n.path("topSessions").isArray() && n.path("topSessions").size() >= 1, r.body());
        // 字段名与 /metrics 一致：运营台没有自己的一套命名，否则"面板上的数字和接口里的
        // 不是一个东西"就成了常驻问题
    }

    @Test
    @DisplayName("/admin 是页面，/admin/overview 是数据：两者都在，且都是只读")
    void adminPageServed() throws Exception {
        start();
        HttpResponse<String> page = get("/admin");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<html"), "应当是 HTML 页面");
        assertTrue(page.body().contains("/admin/overview"), "页面要指向那个只读端点");

        // 运营台没有写端点：POST /admin/overview 必须 404（而不是 500 或者悄悄改了什么）
        HttpResponse<String> post = CLIENT.send(
                HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/admin/overview"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(404, post.statusCode(), "运营台刻意只读：改配置请改环境变量再重启");
    }
}
