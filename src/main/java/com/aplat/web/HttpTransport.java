package com.aplat.web;

import com.aplat.hitl.InteractiveHitl;
import com.aplat.hitl.PendingApproval;
import com.aplat.kernel.Subscription;
import com.aplat.loop.TurnResult;
import com.aplat.run.Platform;
import com.aplat.seam.HitlDecision;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Sandbox;
import com.aplat.seam.SessionEvent;
import com.aplat.seam.Tool;
import com.aplat.seam.SessionLog;
import com.aplat.session.AgUiProjector;
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
import java.nio.file.Path;
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
    private final UiAssets uiAssets;
    private final RateLimiter rateLimiter;
    private final AuditLog auditLog;
    /**
     * 交互式确认队列（U12）。为 null = 当前装配的 HITL 是自动实现（autoAllow/autoDeny）。
     *
     * <p>用 {@code instanceof} 而不是让 {@link com.aplat.seam.Hitl} 长出
     * "查询待办 / 提交决定"两个方法：那两个操作只对"会停下来等人"的实现有意义，
     * 塞进能力缝会让"自动放行"也得实现一堆空方法——能力缝应当只有一个真实的门控方法。
     */
    private final InteractiveHitl approvals;
    private final HttpServer server;
    private final ScheduledExecutorService heartbeat;
    private final Set<SseWriter> activeStreams = ConcurrentHashMap.newKeySet();
    private final long startedAt = System.currentTimeMillis();

    public HttpTransport(Platform platform, ServerConfig config) throws IOException {
        this(platform, config, UiAssets.fromEnv());
    }

    public HttpTransport(Platform platform, ServerConfig config, UiAssets uiAssets) throws IOException {
        this.platform = platform;
        this.config = config;
        this.guard = new ApiKeyGuard(config.apiKey());
        this.uiAssets = uiAssets;
        this.rateLimiter = new RateLimiter(config.rateLimitPerMin());
        this.auditLog = config.auditFile() == null
                ? AuditLog.inMemory()
                : AuditLog.toFile(java.nio.file.Path.of(config.auditFile()));
        this.approvals = platform.hitl() instanceof InteractiveHitl interactive ? interactive : null;
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

    /** 免鉴权的路径：探针与静态资源。API 面一律要 key。 */
    private static boolean isPublic(String path) {
        return path.equals("/health")
                || path.equals("/")
                || path.equals("/index.html")
                || path.equals("/favicon.ico")
                || path.equals("/ui")
                || path.startsWith(UiAssets.URL_PREFIX);
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

    /** 前端未构建时的提示，供启动日志使用。 */
    public java.util.Optional<String> uiAssetsHint() {
        return uiAssets.hintIfMissing();
    }

    /** 审计日志（包内可见：测试要直接断言记录内容，避免为了读它再多花一个限流令牌）。 */
    AuditLog auditLog() {
        return auditLog;
    }

    @Override
    public void close() {
        for (SseWriter w : activeStreams) {
            w.close("server shutdown");
        }
        activeStreams.clear();
        heartbeat.shutdownNow();
        server.stop(0);
        auditLog.close();
    }

    // ---------------------------------------------------------------- routing

    private void route(HttpExchange ex) {
        // 注意：这里**不能**用 try-with-resources 管 exchange。
        // try-with-resources 会在异常抛出时先把资源关掉，再进 catch —— 那时 exchange 已关闭，
        // 任何补救应答都会以 "stream is closed" 失败，客户端只能看到"连接被重置"。
        // 正确顺序是：catch 里先回应答，finally 再关。
        // 请求现场：身份、对端、最终状态码——审计要用（见 RequestScope 的注释）
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        String clientIp = clientIpOf(ex);
        java.util.Optional<String> identified =
                guard.identify(ex.getRequestHeaders()::getFirst, query(ex).get("apiKey"));
        // 认不出身份就记 anonymous，**不要**记成"(rejected)"——
        // 身份字段回答的是"谁"，不是"结果"。把结果塞进身份会让
        // "免鉴权的公开资源被放行（200）"也看起来像被拒（我第一版就是这么错的，
        // 翻审计时看到 `GET /ui/ 200 id=(rejected)` 才反应过来）。
        // 结果由 status 与 note 表达：401 + note=unauthorized 才是真的被拒。
        RequestScope scope = RequestScope.begin(
                identified.orElse(ApiKeyGuard.ANONYMOUS), clientIp, method, path);
        long startedAt = System.nanoTime();

        try {
            applyCommonHeaders(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                RequestScope.current().ifPresent(sc -> sc.responded(204));
                return;
            }

            // 静态资源与探针免鉴权、也免限流：它们本身不含敏感信息，也不产生副作用。
            // 真正的保护在 API 面（需要带 key 且计入限流）。
            boolean publicPath = isPublic(path) && method.equals("GET");
            if (publicPath) {
                scope.note(RequestScope.NOTE_PUBLIC);
            } else if (identified.isEmpty()) {
                scope.note("unauthorized");
                sendError(ex, 401, "UNAUTHORIZED", "missing or invalid API key");
                return;
            } else if (!allowRate(ex, scope, identified.get(), clientIp)) {
                return;
            }

            if (path.equals("/health") && method.equals("GET")) {
                sendJson(ex, 200, health());
            } else if ((path.equals("/") || path.equals("/index.html")) && method.equals("GET")) {
                sendConsole(ex);
            } else if ((path.equals("/ui") || path.startsWith(UiAssets.URL_PREFIX)) && method.equals("GET")) {
                sendUiAsset(ex, path);
            } else if (path.equals("/kernel") && method.equals("GET")) {
                sendJson(ex, 200, kernelReport());
            } else if (path.equals("/tools") && method.equals("GET")) {
                sendJson(ex, 200, toolsReport());
            } else if (path.equals("/audit") && method.equals("GET")) {
                sendJson(ex, 200, auditReport(query(ex)));
            } else if (path.equals("/hitl/pending") && method.equals("GET")) {
                sendJson(ex, 200, pendingApprovals(query(ex)));
            } else if (path.startsWith("/hitl/") && method.equals("POST")) {
                handleApproval(ex, path.substring("/hitl/".length()));
            } else if (path.equals("/run") && method.equals("POST")) {
                handleRun(ex);
            } else if (path.equals("/agui/run") && method.equals("POST")) {
                handleAgUiRun(ex);
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
            recordAudit(scope, startedAt);
            RequestScope.end();
            ex.close();
        }
    }

    /** 记一条审计。绝不抛异常、绝不阻塞（见 AuditLog 的设计约束）。 */
    private void recordAudit(RequestScope scope, long startedAtNanos) {
        try {
            long ms = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            auditLog.record(new RequestAudit(
                    java.time.Instant.now(), scope.identity, scope.clientIp,
                    scope.method, scope.path, scope.status(), ms, scope.note()));
        } catch (Exception e) {
            log("audit failed: " + e);
        }
    }

    /**
     * 限流闸。
     *
     * <p>未启用鉴权时按 **IP** 隔离，否则所有人共用一个 {@code anonymous} 桶——
     * 一个人刷满就把别人全挡住了。
     */
    private boolean allowRate(HttpExchange ex, RequestScope scope, String identity, String clientIp)
            throws IOException {
        if (!config.rateLimitEnabled()) {
            return true;
        }
        String key = guard.enabled() ? identity : clientIp;
        if (rateLimiter.tryAcquire(key)) {
            return true;
        }
        long retry = Math.max(1, rateLimiter.retryAfterSeconds(key));
        scope.note("rate_limited");
        ex.getResponseHeaders().set("Retry-After", String.valueOf(retry));
        sendError(ex, 429, "RATE_LIMITED",
                "too many requests (limit " + config.rateLimitPerMin() + "/min); retry after " + retry + "s");
        return false;
    }

    private static String clientIpOf(HttpExchange ex) {
        try {
            var addr = ex.getRemoteAddress();
            return addr == null ? "-" : addr.getAddress().getHostAddress();
        } catch (Exception e) {
            return "-";
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

    // ------------------------------------------------------- AG-UI 契约面（U08）

    /**
     * AG-UI 标准端点：{@code POST /agui/run} + SSE。
     *
     * <p>这是"对外只讲 AG-UI"的那条线——{@code @ag-ui/client} 的 {@code HttpAgent}、
     * CopilotKit 都按这个协议说话。与 {@code /agui/stream} 的区别是<b>事件形状</b>：
     * 那条走本平台信封（{@code {type, sessionId, seq, payload}}），这条走规范字段
     * （{@code threadId / messageId / delta / toolCallId}）。
     *
     * <p>两个协议细节值得说明：
     * <ul>
     *   <li><b>不回填历史</b>：客户端自带 thread 历史并负责渲染，服务端重放会让 UI 看到重复消息。
     *       所以水位直接设在"当前日志末尾"——订阅生效但不补历史。</li>
     *   <li><b>历史转 LlmMessage 喂给循环</b>：否则多轮对话每轮都从零开始。</li>
     * </ul>
     */
    private void handleAgUiRun(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            sendError(ex, 400, "INVALID_JSON", "request body must be a RunAgentInput JSON object");
            return;
        }
        AgUiRunInput input = AgUiRunInput.parse(root);
        if (input.userInput() == null) {
            sendError(ex, 400, "MISSING_INPUT", "messages must contain a non-empty user message");
            return;
        }

        SseWriter writer = null;
        Subscription subscription = null;
        try {
            writer = startSse(ex);
            activeStreams.add(writer);

            AgUiProjector projector = new AgUiProjector(input.threadId(), input.runId());
            final SseWriter w = writer;
            EventPump pump = new EventPump(event -> {
                boolean first = true;
                for (Map<String, Object> projected : projector.project(event)) {
                    String type = String.valueOf(projected.get("type"));
                    String json = Json.write(projected);
                    // 同一个内部事件可能产出多条 AG-UI 事件；id 只挂在第一条上，
                    // 保证 id 唯一且单调——否则 Last-Event-ID 定位会错位
                    boolean sent = first
                            ? w.event(event.seq(), type, json)
                            : w.event(type, json);
                    first = false;
                    if (!sent) {
                        return false;
                    }
                }
                return true;
            });

            SessionLog log = platform.sessionLog();
            String sessionId = input.threadId();
            long resumeFrom = log.events(sessionId).size();
            subscription = log.subscribe(sessionId, pump);
            pump.beginBackfill(resumeFrom);
            pump.goLive();

            log("agui run thread=" + sessionId + " run=" + input.runId()
                    + " historyTurns=" + input.history().size()
                    + " input=" + abbreviate(input.userInput()));

            TurnResult result = platform.loop().run(sessionId, input.history(), input.userInput());

            // 收尾：关掉仍然打开的文本消息（否则前端的光标会一直闪）
            for (Map<String, Object> projected : projector.finish()) {
                w.event(String.valueOf(projected.get("type")), Json.write(projected));
            }
            log("agui run done thread=" + sessionId + " status=" + result.status());
        } catch (Exception e) {
            if (writer != null) {
                writer.event("RUN_ERROR", Json.write(Map.of(
                        "type", "RUN_ERROR",
                        "message", String.valueOf(e.getMessage()),
                        "code", "TRANSPORT_ERROR")));
            }
            log("agui run failed: " + e);
        } finally {
            release(subscription, writer, ex, "run finished");
        }
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
        // raw=1：不写 event 字段，全部走 onmessage（给"要看全部事件"的时间线用）
        boolean rawFrames = "1".equals(q.get("raw")) || "true".equals(q.get("raw"));

        SseWriter writer = null;
        Subscription subscription = null;
        try {
            writer = startSse(ex, !rawFrames);
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
        return startSse(ex, true);
    }

    /**
     * @param namedEvents false = 不写 {@code event:} 字段，让客户端一律走 {@code onmessage}。
     *                    排查用的时间线要"看全部事件"，具名派发会逼它枚举事件名，
     *                    后端一加新类型界面就静默少一条——所以那里走无名帧。
     */
    private SseWriter startSse(HttpExchange ex, boolean namedEvents) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache, no-transform");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no"); // 让 nginx 等反向代理不要缓冲，否则流式会变成整段返回
        ex.sendResponseHeaders(200, 0); // 0 = chunked，长度未知
        // 注意：SSE 不走 sendBytes，所以状态码得在这里自己记一笔。
        // 漏了这一步的话，所有流式请求在审计里都会记成 status=0（"未及应答"），
        // 看起来全是失败——这恰恰是审计最不该犯的错，靠翻审计日志才发现。
        RequestScope.current().ifPresent(scope -> scope.responded(200));
        return new SseWriter(ex.getResponseBody(), namedEvents);
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
        out.put("ui", uiAssets.available() ? "built" : "not built (run: cd ui && npm run build)");
        out.put("rateLimit", config.rateLimitEnabled()
                ? config.rateLimitPerMin() + "/min per caller" : "disabled");
        out.put("hitl", approvals == null
                ? platform.hitl().id()
                : platform.hitl().id() + " · " + approvals.pending().size() + " pending");
        out.put("audit", (auditLog.file() == null ? "memory only" : auditLog.file().toString())
                + " · total=" + auditLog.total() + " dropped=" + auditLog.dropped());
        return out;
    }

    /**
     * 工具清单（管理面 / 前端用）。
     *
     * <p>前端靠它决定"有哪些工具、哪个需要人批准、哪个会执行命令"——
     * 从而**不必把工具名硬编码在界面代码里**。加一个新工具，界面自动跟上。
     */
    private Map<String, Object> toolsReport() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Tool tool : platform.kernel().ctx().get(com.aplat.seam.ToolRegistry.class).all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tool.name());
            row.put("description", tool.spec().description());
            row.put("approvalRequired", tool.approvalRequired());
            row.put("executesCommands", tool.spec().executesCommands());
            row.put("commandField", tool.spec().commandField());
            tools.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", tools);
        out.put("count", tools.size());
        return out;
    }

    // -------------------------------------------------------- 人工确认面（U12/U13）

    /**
     * 待确认队列。
     *
     * <p>这是"人在另一个设备上"也能用的关键：{@code POST /run} 在等确认时会一直挂在那儿，
     * 而任何一方都可以用它看到"现在有几条在等人、分别是什么"，再决定放不放。
     */
    private Map<String, Object> pendingApprovals(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interactive", approvals != null);
        if (approvals == null) {
            out.put("mode", "non-interactive");
            out.put("count", 0);
            out.put("pending", List.of());
            out.put("note", "当前装配的 Hitl 实现不等人（autoAllow/autoDeny），没有待确认队列");
            return out;
        }
        out.put("mode", approvals.mode().name().toLowerCase());
        out.put("timeoutSec", approvals.timeout().toSeconds());

        String sessionId = q.get("sessionId");
        List<PendingApproval> list = sessionId == null || sessionId.isBlank()
                ? approvals.pending()
                : approvals.pendingFor(sessionId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PendingApproval p : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestId", p.requestId());
            row.put("sessionId", p.sessionId());
            row.put("tool", p.toolName());
            row.put("args", p.argumentsJson());
            row.put("reason", p.reason());
            row.put("requestedAt", p.requestedAt().toString());
            row.put("expiresAt", p.expiresAt().toString());
            row.put("remainingMs", p.remainingMillis());
            rows.add(row);
        }
        out.put("count", rows.size());
        out.put("pending", rows);
        return out;
    }

    /**
     * 提交一个人的决定。
     *
     * <p>四个可选值就是人在界面上能做的四件事：{@code once} / {@code always} / {@code deny} /
     * {@code modify}。<b>没有 {@code timeout}</b>——那是"系统等不到人"的状态，
     * 客户端不该有权伪造它（{@code InteractiveHitl.resolve} 会拒）。
     *
     * <p>过期或已答过返回 <b>409</b> 而不是 404：这条请求确实存在过，只是不再可答。
     * 这个区别对前端有意义——409 时界面应当把卡片撤掉并提示"已超时"，而不是报错误。
     */
    private void handleApproval(HttpExchange ex, String rawRequestId) throws IOException {
        if (approvals == null) {
            sendError(ex, 501, "HITL_NOT_INTERACTIVE",
                    "the assembled HITL implementation does not wait for humans; "
                            + "start with " + InteractiveHitl.ENV_MODE + "=ask to enable approvals");
            return;
        }
        String requestId = URLDecoder.decode(rawRequestId, StandardCharsets.UTF_8);
        if (requestId.isBlank()) {
            sendError(ex, 400, "MISSING_REQUEST_ID", "request id is required in path");
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            sendError(ex, 400, "INVALID_JSON", "request body must be a JSON object");
            return;
        }

        String kind = text(node, "decision");
        if (kind == null) {
            sendError(ex, 400, "MISSING_DECISION",
                    "field 'decision' is required: once | always | deny | modify");
            return;
        }
        kind = kind.trim().toLowerCase();

        HitlDecision decision;
        switch (kind) {
            case "once", "approve", "allow" -> decision = new HitlDecision.Once();
            case "always", "approve-always" -> decision = new HitlDecision.Always();
            case "deny", "reject" -> {
                String reason = text(node, "reason");
                decision = new HitlDecision.Deny(reason == null || reason.isBlank()
                        ? "denied by operator" : reason);
            }
            case "modify", "modified" -> {
                String args = text(node, "arguments");
                if (args == null || args.isBlank()) {
                    sendError(ex, 400, "MISSING_ARGUMENTS",
                            "decision=modify requires field 'arguments' (a JSON object)");
                    return;
                }
                decision = new HitlDecision.Modified(args);
            }
            default -> {
                sendError(ex, 400, "UNKNOWN_DECISION",
                        "unknown decision '" + kind + "'; expected once | always | deny | modify");
                return;
            }
        }

        if (!approvals.resolve(requestId, decision)) {
            sendError(ex, 409, "NOT_PENDING",
                    "no pending approval '" + requestId + "' (already answered, expired, or unknown)");
            return;
        }
        log("hitl resolved id=" + requestId + " decision=" + kind);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("requestId", requestId);
        ok.put("decision", kind);
        ok.put("accepted", true);
        sendJson(ex, 200, ok);
    }

    /**
     * 审计查询（U17）：谁 / 何时 / 调了什么 / 结果。
     *
     * <p>注意这里也受鉴权保护——审计记录本身就是敏感信息（能看出谁在用、在调什么）。
     */
    private Map<String, Object> auditReport(Map<String, String> q) {
        int limit = (int) Math.max(1, Math.min(500, parseLong(q.get("limit"), 50)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RequestAudit a : auditLog.recent(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", a.ts().toString());
            row.put("identity", a.identity());
            row.put("ip", a.clientIp());
            row.put("method", a.method());
            row.put("path", a.path());
            row.put("status", a.status());
            row.put("durationMs", a.durationMs());
            row.put("note", a.note());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", auditLog.total());
        out.put("dropped", auditLog.dropped());
        out.put("persisted", auditLog.file() == null ? null : auditLog.file().toString());
        out.put("count", rows.size());
        out.put("records", rows);
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

    /** 托管前端构建产物（{@code ui/dist}）。路径穿越与 SPA 回退都在 {@link UiAssets} 里处理。 */
    private void sendUiAsset(HttpExchange ex, String path) throws IOException {
        if (!uiAssets.available()) {
            sendError(ex, 404, "UI_NOT_BUILT",
                    uiAssets.hintIfMissing().orElse("ui/dist not found"));
            return;
        }
        Path file = uiAssets.resolve(path);
        if (file == null) {
            sendError(ex, 404, "NOT_FOUND", "no such asset: " + path);
            return;
        }
        // 本地开发工具：宁可每次都重新取，也不要发到一半发现是上一版前端
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(ex, 200, UiAssets.contentTypeOf(file), uiAssets.read(file));
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
        RequestScope.current().ifPresent(scope -> scope.responded(status));
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
