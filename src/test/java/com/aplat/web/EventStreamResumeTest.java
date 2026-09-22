package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U09 的传输层验收：**断线重连后事件无缺无重**。
 *
 * <p>验收方式刻意做成"可复现"的：先跑完一个 turn 拿到完整事件序列，再从任意中间游标续传，
 * 断言续传结果恰好等于"全量序列里游标之后的那一段"。这样无论以后事件怎么增删，
 * 只要这段等式成立，续传就是对的。
 */
class EventStreamResumeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private HttpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    private void start(long heartbeatMillis) throws Exception {
        Platform platform = Platform.assemble(
                new ScriptedLlmAdapter()
                        .thenToolCall("shell", "{\"command\":\"echo resume\"}")
                        .thenText("完成：resume"),
                new ProcessSandbox(), Hitl.autoAllow(), LoopBudget.defaults());
        transport = new HttpTransport(platform,
                ServerConfig.defaults().withPort(0).withHeartbeatMillis(heartbeatMillis)).start();
    }

    /** 先跑完一个 turn，返回全量事件的 seq 列表。 */
    private List<Long> seedSession(String sessionId) throws Exception {
        CLIENT.send(HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/run"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"input\":\"跑一条命令\",\"sessionId\":\"" + sessionId + "\"}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode body = MAPPER.readTree(CLIENT.send(
                        HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/sessions/" + sessionId + "/events"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .body());
        List<Long> seqs = new ArrayList<>();
        body.get("events").forEach(e -> seqs.add(e.get("seq").asLong()));
        return seqs;
    }

    private static List<Long> idsOf(List<SseTestClient.Frame> frames) {
        return frames.stream().map(SseTestClient.Frame::id).toList();
    }

    @Test
    @DisplayName("从中间游标续传：拿到的是全量序列里紧跟其后的那一段，无缺无重")
    void resumeFromMiddleCursorIsExact() throws Exception {
        start(500);
        List<Long> all = seedSession("s-resume");
        assertTrue(all.size() > 5, "这次 turn 应该产生了足够多的事件用于续传验证");

        for (long cursor : new long[]{0, 3, 7}) {
            List<SseTestClient.Frame> resumed = SseTestClient.collect(
                    transport.baseUrl() + "/agui/events/s-resume?lastEventId=" + cursor + "&once=turn.closed",
                    Duration.ofSeconds(15));

            long last = all.get(all.size() - 1);
            List<Long> expected = LongStream.rangeClosed(cursor + 1, last).boxed().toList();
            assertEquals(expected, idsOf(resumed),
                    "从 " + cursor + " 续传的结果必须恰好等于全量序列的后续部分");
        }
    }

    @Test
    @DisplayName("浏览器路径：Last-Event-ID 请求头与查询参数等价")
    void lastEventIdHeaderIsHonoured() throws Exception {
        start(500);
        List<Long> all = seedSession("s-header");

        List<SseTestClient.Frame> resumed = SseTestClient.collect(
                transport.baseUrl() + "/agui/events/s-header?once=turn.closed",
                "Last-Event-ID", "5", Duration.ofSeconds(15));

        long last = all.get(all.size() - 1);
        assertEquals(LongStream.rangeClosed(6, last).boxed().toList(), idsOf(resumed));
    }

    @Test
    @DisplayName("同一会话可以多端同时观看：两条流各自拿到完整序列，互不影响")
    void multipleWatchersEachGetFullStream() throws Exception {
        start(500);
        List<Long> all = seedSession("s-multi");
        long last = all.get(all.size() - 1);

        List<SseTestClient.Frame> a = SseTestClient.collect(
                transport.baseUrl() + "/agui/events/s-multi?once=turn.closed", Duration.ofSeconds(15));
        List<SseTestClient.Frame> b = SseTestClient.collect(
                transport.baseUrl() + "/agui/events/s-multi?once=turn.closed", Duration.ofSeconds(15));

        assertEquals(LongStream.rangeClosed(1, last).boxed().toList(), idsOf(a));
        assertEquals(idsOf(a), idsOf(b), "第二条流不受第一条流关闭的影响");
    }

    @Test
    @DisplayName("客户端断开后订阅必须被回收——否则连接泄漏是必然的")
    void disconnectReleasesSubscription() throws Exception {
        start(200);
        seedSession("s-drop");

        // 用裸 socket 连，读到数据后直接 close：这是最真实的"用户关掉页面"
        try (Socket socket = new Socket("127.0.0.1", transport.port())) {
            OutputStream os = socket.getOutputStream();
            os.write(("GET /agui/events/s-drop HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + transport.port() + "\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int seen = 0;
            while ((line = reader.readLine()) != null && seen < 6) {
                if (line.startsWith("id: ") || line.startsWith("event: ")) {
                    seen++;
                }
            }
            assertTrue(seen > 0, "断连前应已经收到过事件");
        }

        // 心跳周期 200ms，给两轮时间让服务端发现写失败
        long deadline = System.currentTimeMillis() + 5_000;
        int active = -1;
        while (System.currentTimeMillis() < deadline) {
            active = MAPPER.readTree(CLIENT.send(
                            HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/health")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .body()).get("activeStreams").asInt();
            if (active == 0) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(0, active, "对端断开后 activeStreams 必须回到 0");
    }
}
