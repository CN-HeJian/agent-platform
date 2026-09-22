package com.aplat.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 测试用的极简 SSE 客户端。
 *
 * <p>为什么不引 WebTestClient：U02 的验收点之一就是"零新增依赖"，
 * 测试端如果先破例，这条约束就没意义了。JDK 的 {@code HttpClient} 足够。
 *
 * <p>两个必须处理的坑：
 * <ul>
 *   <li>SSE 是无限流，直接 {@code send()} 会挂住——所以读取放到另一条线程并加超时；</li>
 *   <li>按行解析必须认 {@code id:} / {@code event:} / {@code data:} 三个字段，
 *       不能把 {@code data} 当整行 JSON（那是 HTTP 不是 SSE）。</li>
 * </ul>
 */
final class SseTestClient {

    record Frame(Long id, String event, String data) {

        /** 取 data 里的某个字段（测试里懒得引 JSON 库）。 */
        boolean dataContains(String needle) {
            return data != null && data.contains(needle);
        }
    }

    private SseTestClient() {
    }

    /** 读一条会自行结束的 SSE 流（例如带 {@code once=} 或 turn 跑完）。 */
    static List<Frame> collect(String url, Duration timeout) {
        return collect(url, null, null, timeout);
    }

    /** 带一个自定义请求头——用于验证浏览器自动回传 {@code Last-Event-ID} 的那条路。 */
    static List<Frame> collect(String url, String headerName, String headerValue, Duration timeout) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-test-reader");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<List<Frame>> f = exec.submit(() -> read(url, headerName, headerValue));
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("SSE 流未在 " + timeout + " 内结束：" + url, e);
        } catch (ExecutionException e) {
            throw new AssertionError("读取 SSE 失败：" + url, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("被中断", e);
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * POST 一个 JSON 体并读完整条 SSE 流。
     *
     * <p>AG-UI 的入口是 {@code POST /agui/run}（{@code EventSource} 只能 GET，
     * 所以真实前端用的是 fetch + ReadableStream——这里同理）。
     */
    static List<Frame> collectPost(String url, String bodyJson, Duration timeout) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-test-reader");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<List<Frame>> f = exec.submit(() -> readPost(url, bodyJson));
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("POST 的 SSE 流未在 " + timeout + " 内结束：" + url, e);
        } catch (ExecutionException e) {
            throw new AssertionError("读取 POST SSE 失败：" + url, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("被中断", e);
        } finally {
            exec.shutdownNow();
        }
    }

    /** 打开一条流但不等它结束——用于"断连后资源是否回收"这类测试。 */
    static InputStream open(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofInputStream()).body();
    }

    private static List<Frame> read(String url, String headerName, String headerValue)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (headerName != null) {
            builder.header(headerName, headerValue);
        }
        HttpResponse<InputStream> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return parse(resp);
    }

    private static List<Frame> readPost(String url, String bodyJson) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        return parse(client.send(req, HttpResponse.BodyHandlers.ofInputStream()));
    }

    private static List<Frame> parse(HttpResponse<InputStream> resp) throws IOException {
        List<Frame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            Map<String, String> fields = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!fields.isEmpty()) {
                        frames.add(toFrame(fields));
                        fields = new LinkedHashMap<>();
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue; // 注释（心跳）
                }
                int colon = line.indexOf(':');
                String name = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1).stripLeading();
                fields.put(name, value);
            }
            if (!fields.isEmpty()) {
                frames.add(toFrame(fields));
            }
        }
        return frames;
    }

    private static Frame toFrame(Map<String, String> fields) {
        String rawId = fields.get("id");
        return new Frame(rawId == null ? null : Long.parseLong(rawId), fields.get("event"), fields.get("data"));
    }
}
