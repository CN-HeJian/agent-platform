package com.aplat.web;

import com.aplat.kernel.Subscription;
import com.aplat.loop.TurnResult;
import com.aplat.run.Platform;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Sandbox;
import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import com.aplat.tools.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * HTTP + SSE 传输层（U02）。
 *
 * <p>零新增依赖：JDK 自带的 {@code com.sun.net.httpserver.HttpServer} + 虚拟线程。
 * 之所以不引框架，是因为传输层的职责本来就窄——<b>把会话事件投影成 AG-UI 事件流</b>，
 * 一个路由表就够；引 Spring MVC/WebFlux 会让"薄内核"多背一整套容器。
 *
 * <p>接口面（设计基线里的"对外只讲 AG-UI"就是这三条流）：
 * <pre>
 *   GET  /health                          健康与装配自检
 *   GET  /kernel                          装配清单（排查第一站）
 *   POST /run                             {"sessionId?","input"} → 同步跑完一个 turn，返回 JSON
 *   GET  /agui/stream?input=&amp;sessionId=   跑一个 turn 并 SSE 流式吐 AG-UI 事件
 *   GET  /agui/events/{sessionId}?lastEventId=  纯事件面：回填 + 实时，支持断线续传
 *   GET  /sessions/{sessionId}/events     回放为 JSON（离线排查）
 *   GET  /                                内置调试控制台（不是 U07 的正式前端）
 * </pre>
 *
 * <p>关键设计取舍：<b>事件流与 turn 执行是两条独立的入口</b>。
 * {@code /agui/stream} 只是"订阅 + 触发一次 turn"的组合，而 {@code /agui/events} 完全不碰循环——
 * 所以前端断线重连、多端同时观看、事后补看，走的都是同一条路，不需要后端做特例。
 */
public final class HttpTransport implements AutoCloseable {

    private static final String CONSOLE_RESOURCE = "/web/console.html";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Platform platform;
    private final ServerConfig config;
    private final ApiKeyGuard guard;
    private final HttpServer server;
    private final ScheduledExecutorService heartbeat;
    private final Set<SseWriter> activeStreams = ConcurrentHashMap.newKeySet();
    private final long startedAt = System.currentTimeMillis();

    public HttpTransport(Platform platform, ServerConfig config) throws IOException {
        this.platform = platform;
        this.config = config;
        this.guard = new ApiKeyGuard(config.apiKey());
        this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        // 每个请求一条虚拟线程：SSE 处理要长时间阻塞在等事件上，平台线程池会被瞬间占满
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::route);
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public HttpTransport start() {
        server.start();
        heartbeat.scheduleAtFixedRate(this::heartbeatAll,
                config.heartbeatMillis(), config.heartbeatMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        String h = config.host().contains(":") ? "[" + config.host() + "]" : config.host();
        return "http://" + h + ":" + port();
    }

    public ServerConfig config() {
        return config;
    }

    @Override
    public void close() {
        for (SseWriter w : activeStreams) {
            w.close("server shutdown");
        }
        activeStreams.clear();
        heartbeat.shutdownNow();
        server.stop(0);
    }

    // ---------------------------------------------------------------- routing

    private void route(HttpExchange ex) {
        // 注意：这里**不能**用 try-with-resources 管 exchange。
        // try-with-resources 会在异常抛出时先把资源关掉，再进 catch —— 那时 exchange 已关闭，
        // 任何补救应答都会以 "stream is closed" 失败，客户端只能看到"连接被重置"。
        // 正确顺序是：catch 里先回应答，finally 再关。
        try {
            applyCommonHeaders(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase();

            if (path.equals("/health") && method.equals("GET")) {
                sendJson(ex, 200, health());
                return;
            }
            if (!guard.allowed(ex.getRequestHeaders()::getFirst, query(ex).get("apiKey"))) {
                sendError(ex, 401, "UNAUTHORIZED", "missing or invalid API key");
                return;
            }

            if ((path.equals("/") || path.equals("/index.html")) && method.equals("GET")) {
                sendConsole(ex);
            } else if (path.equals("/kernel") && method.equals("GET")) {
                sendJson(ex, 200, kernelReport());
            } else if (path.equals("/run") && method.equals("POST")) {
                handleRun(ex);
            } else if (path.equals("/agui/stream") && method.equals("GET")) {
                handleStream(ex);
            } else if (path.startsWith("/agui/events/") && method.equals("GET")) {
                handleEvents(ex, path.substring("/agui/events/".length()));
            } else if (path.startsWith("/sessions/") && path.endsWith("/events") && method.equals("GET")) {
                handleSessionEvents(ex, path.substring("/sessions/".length(), path.length() - "/events".length()));
            } else {
                sendError(ex, 404, "NOT_FOUND", "no route for " + method + " " + path);
            }
        } catch (Exception e) {
            // 传输层绝不因为单个请求异常而影响其它连接。
            // 但也不能只记日志了事——那会让客户端看到"连接被重置"而不是错误原因，
            // 排查时极易误判成网络问题（这个坑本项目已经踩过一次）。
            log("handler failed: " + e);
            try {
                sendError(ex, 500, "INTERNAL", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception nested) {
                // SSE 已经开始写、Headers 已发出，此时只能任由连接收摊。
                // 但绝不静默：把"为什么连 500 都发不出去"也记下来。
                log("could not send 500: " + nested);
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------- turn 执行面

    /** 同步跑完一个 turn。适合脚本、CI、以及"我只想要最终答案"的调用方。 */
    private void handleRun(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            sendError(ex, 400, "INVALID_JSON", "request body must be a JSON object");
            return;
        }
        String input = text(node, "input");
        if (input == null || input.isBlank()) {
            sendError(ex, 400, "MISSING_INPUT", "field 'input' is required");
            return;
        }
        String sessionId = orNewSession(text(node, "sessionId"));

        log("run session=" + sessionId + " input=" + abbreviate(input));
        TurnResult result = platform.loop().run(sessionId, input);
        sendJson(ex, 200, summarise(result));
    }

    // ------------------------------------------------------------- SSE 流式面

    /** 跑一个 turn，并把它的 AG-UI 事件按发生顺序实时吐出来。 */
    private void handleStream(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String input = q.get("input");
        if (input == null || input.isBlank()) {
            sendError(ex, 400, "MISSING_INPUT", "query param 'input' is required");
            return;
        }
        String sessionId = orNewSession(q.get("sessionId"));
        long afterSeq = parseLong(q.get("lastEventId"), 0L);

        SseWriter writer = null;
        Subscription subscription = null;
        try {
            writer = startSse(ex);
            activeStreams.add(writer);

            EventPump pump = new EventPump(writer);
            subscription = bind(pump, sessionId, afterSeq);

            log("stream session=" + sessionId + " input=" + abbreviate(input));
            writer.event("RUN_REQUESTED", Json.write(Map.of("sessionId", sessionId, "input", input)));

            TurnResult result = platform.loop().run(sessionId, input);

            // turn.closed 已经把 RUN_FINISHED 送出去了，这里只补一条结构化回执，
            // 便于前端拿到 status/steps 而不必回来再查一次
            writer.event("RUN_RESULT", Json.write(summarise(result)));
        } catch (Exception e) {
            if (writer != null) {
                writer.event("RUN_ERROR", Json.write(Map.of("message", String.valueOf(e.getMessage()))));
            }
            log("stream failed: " + e);
        } finally {
            release(subscription, writer, ex, "turn finished");
        }
    }

    /** 纯事件面：先补齐历史（&gt; lastEventId），再转实时。不触发任何 turn。 */
    private void handleEvents(HttpExchange ex, String rawSessionId) throws IOException {
        Map<String, String> q = query(ex);
        String sessionId = URLDecoder.decode(rawSessionId, StandardCharsets.UTF_8);
        if (sessionId.isBlank()) {
            sendError(ex, 400, "MISSING_SESSION", "sessionId is required in path");
            return;
        }
        // 优先用查询参数；没有再退回标准的 Last-Event-ID 头（浏览器 EventSource 自动带）
        long lastEventId = parseLong(q.getOrDefault("lastEventId",
                ex.getRequestHeaders().getFirst("Last-Event-ID")), 0L);
        String once = q.get("once");

        SseWriter writer = null;
        Subscription subscription = null;
        try {
            writer = startSse(ex);
            activeStreams.add(writer);

            EventPump pump = new EventPump(writer);
            final SseWriter w = writer;
            // once=turn.closed 之类：写到要看的那条就收摊，方便 curl 与测试
            pump.onWritten(event -> {
                if (once != null && once.equals(event.type())) {
                    w.close("once:" + once);
                }
            });
            subscription = bind(pump, sessionId, lastEventId);

            log("events session=" + sessionId + " lastEventId=" + lastEventId
                    + " replay=" + pump.written() + " once=" + once);

            // 保持连接：心跳失败或对端断开都会在这里被唤醒
            writer.awaitClosed(Long.MAX_VALUE / 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log("events failed: " + e);
        } finally {
            release(subscription, writer, ex, "client gone");
        }
    }

    /** 回放成 JSON —— 排查时不方便挂 SSE 客户端，直接取整体。 */
    private void handleSessionEvents(HttpExchange ex, String rawSessionId) throws IOException {
        String sessionId = URLDecoder.decode(rawSessionId.replace("/", ""), StandardCharsets.UTF_8);
        long afterSeq = parseLong(query(ex).get("lastEventId"), 0L);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionEvent e : platform.sessionLog().eventsAfter(sessionId, afterSeq)) {
            out.add(com.aplat.session.AgUiMapper.toAgUi(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("count", out.size());
        body.put("events", out);
        sendJson(ex, 200, body);
    }

    // ------------------------------------------------------------------ helper

    /**
     * 订阅 → 标记回填 → 补齐历史 → 转实时。顺序不能换：先订阅再回填，
     * 否则"回填读库"与"订阅生效"之间的窗口会漏事件（详见 {@link EventPump}）。
     *
     * @return 可回收的订阅句柄
     */
    private Subscription bind(EventPump pump, String sessionId, long afterSeq) {
        SessionLog log = platform.sessionLog();
        Subscription sub = log.subscribe(sessionId, pump);
        pump.beginBackfill(afterSeq);
        pump.backfill(log.eventsAfter(sessionId, afterSeq));
        pump.goLive();
        return sub;
    }

    private SseWriter startSse(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache, no-transform");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no"); // 让 nginx 等反向代理不要缓冲，否则流式会变成整段返回
        ex.sendResponseHeaders(200, 0); // 0 = chunked，长度未知
        return new SseWriter(ex.getResponseBody());
    }

    private void release(Subscription sub, SseWriter writer, HttpExchange ex, String reason) {
        if (sub != null) {
            sub.cancel();
        }
        if (writer != null) {
            writer.close(reason);
            activeStreams.remove(writer);
        }
        try {
            ex.close();
        } catch (Exception ignored) {
            // 对端已断开时 close 会抛，忽略即可
        }
    }

    private void heartbeatAll() {
        for (SseWriter w : activeStreams) {
            if (w.isClosed()) {
                activeStreams.remove(w);
                continue;
            }
            w.heartbeat();
        }
    }

    private Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("uptimeMs", System.currentTimeMillis() - startedAt);
        out.put("llm", platform.kernel().ctx().get(LlmAdapter.class).id());
        out.put("sandbox", platform.kernel().ctx().optional(Sandbox.class)
                .map(Sandbox::description).orElse("(none)"));
        out.put("seams", platform.kernel().registry().boundSeams().size());
        out.put("activeStreams", activeStreams.size());
        out.put("auth", guard.enabled() ? "api-key" : "disabled(loopback only)");
        return out;
    }

    private Map<String, Object> kernelReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assembly", platform.kernel().registry().describe());
        out.put("replaceable", com.aplat.seam.Capabilities.REPLACEABLE.stream()
                .map(Class::getSimpleName).sorted().toList());
        out.put("report", platform.assemblyReport());
        return out;
    }

    private Map<String, Object> summarise(TurnResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", r.sessionId());
        out.put("status", r.status().name());
        out.put("steps", r.steps());
        out.put("finalText", r.finalText());
        out.put("events", r.events().size());
        return out;
    }

    private void sendConsole(HttpExchange ex) throws IOException {
        try (InputStream in = HttpTransport.class.getResourceAsStream(CONSOLE_RESOURCE)) {
            if (in == null) {
                sendError(ex, 404, "NO_CONSOLE", "console resource missing: " + CONSOLE_RESOURCE);
                return;
            }
            sendBytes(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        sendBytes(ex, status, "application/json; charset=utf-8",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(HttpExchange ex, int status, String code, String message) throws IOException {
        sendJson(ex, status, Map.of("error", code, "message", message));
    }

    private void sendBytes(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void applyCommonHeaders(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        if (config.corsAnyOrigin()) {
            h.set("Access-Control-Allow-Origin", "*");
            h.set("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization, Last-Event-ID");
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Expose-Headers", "Content-Type");
        }
        h.set("X-Aplat", "agent-platform/0.1.0");
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String orNewSession(String candidate) {
        return candidate == null || candidate.isBlank()
                ? "s-" + UUID.randomUUID().toString().substring(0, 8)
                : candidate;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    private static void log(String msg) {
        System.out.println("[web] " + msg);
    }
}
