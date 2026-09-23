package com.aplat.hitl;

import com.aplat.seam.Hitl;
import com.aplat.seam.HitlDecision;
import com.aplat.seam.HitlRequest;
import com.aplat.seam.SessionLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会真的停下来等人的 HITL 实现（U12）。
 *
 * <p>这是"工具需批准"从<b>声明</b>变成<b>事实</b>的地方。在此之前，{@code always} 被当
 * {@code once} 处理、也没有任何交互通道——也就是说"需批准"实际等于"总是批准"，
 * 安全语义是空的。这个类补上四件事：
 *
 * <ol>
 *   <li><b>四种决策各自落地</b>：{@code once} / {@code always}（本会话同类放行）/
 *       {@code deny} / {@code modified}（改参后执行），加上超时这条兜底路径。</li>
 *   <li><b>阻塞式等待</b>：调用线程（一条虚拟线程）停在 future 上，
 *       由 {@link #resolve} 唤醒。SSE 长连接正是它的用法，不需要轮询。</li>
 *   <li><b>全程留痕</b>：{@code hitl.requested} / {@code hitl.resolved} 落会话日志，
 *       所以"为什么这次没问我"和"为什么它没执行"都能从回放里看出来。</li>
 *   <li><b>超时是显式状态</b>：不是异常、也不算"人被问了但答错"——
 *       它与 deny 的错误码不同，模型据此可以说"人不在"而不是"人反对"。</li>
 * </ol>
 *
 * <h2>三条值得说明的取舍</h2>
 *
 * <p><b>1. 仲裁与唤醒的顺序：先落事实，再唤醒。</b>
 * "用户刚好在超时那一刻点提交"是必然发生的，所以两边都要争一个 CAS。
 * 更关键的是**谁也不能在写完日志之前唤醒等待者**——被唤醒的循环会立刻继续跑、
 * 立刻去读会话日志，前端也会立刻刷新。顺序反了就会留下"决定已生效、日志里却还没有"
 * 的窗口，而这是审计最不该有的性质。（这条是被"超时与回答撞在一起"的测试逼出来的：
 * 它一开始随机地读到 0 条 resolved 记录。）
 *
 * <p><b>2. 非 ASK 模式也记一条 {@code hitl.auto_approved}。</b>
 * 否则回放里会出现"没人问过就执行了"的空白——那种空白在事故复盘时最费时间。
 * 现在是 {@code mode-allow} 还是 {@code session-allowlist} 一目了然。
 *
 * <p><b>3. {@code always} 的粒度是"本会话 × 本工具 × 任意参数"。</b>
 * 这是它好用的原因，也是它的风险：点一次"总是允许"等于给该会话里这个工具开了永久通道
 * （对 {@code shell} 而言就是"这个会话里随便跑命令"）。所以这个决定必须显式、可审计，
 * 且不跨会话。要更细的粒度得等权限体系（U25）。
 *
 * <p>线程安全：等待表与放行表都是并发的，可以被 HTTP 线程与循环线程同时访问。
 */
public final class InteractiveHitl implements Hitl {

    /** 三种运行模式。默认 {@link #ASK}——一个能执行命令的服务，"先问"才是诚实的默认值。 */
    public enum Mode {
        /** 真的停下来等人回答。 */
        ASK,
        /** 不等人，直接放行。本地调试用（但仍会留痕）。 */
        ALLOW,
        /** 不等人，直接拒绝。批处理/无人值守用。 */
        DENY;

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return switch (raw.trim().toLowerCase()) {
                case "ask", "on", "true", "1" -> ASK;
                case "allow", "auto", "off", "false", "0" -> ALLOW;
                case "deny", "reject" -> DENY;
                default -> fallback;
            };
        }
    }

    public static final String ENV_MODE = "APLAT_HITL";
    public static final String ENV_TIMEOUT_SEC = "APLAT_HITL_TIMEOUT_SEC";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** 系统自用：用户不可能提交这个决策（{@link #resolve} 会拒），它只表示"没人回答"。 */
    private static final HitlDecision TIMED_OUT = new HitlDecision.Timeout();

    private final SessionLog log;
    private final Mode mode;
    private final Duration timeout;
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionAllowlist = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    public InteractiveHitl(SessionLog log, Mode mode, Duration timeout) {
        this.log = log;
        this.mode = mode == null ? Mode.ASK : mode;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_TIMEOUT
                : timeout;
    }

    public static InteractiveHitl fromEnv(SessionLog log, Map<String, String> env) {
        Mode mode = Mode.parse(env.get(ENV_MODE), Mode.ASK);
        long sec = 0;
        try {
            sec = Long.parseLong(String.valueOf(env.getOrDefault(ENV_TIMEOUT_SEC, "")));
        } catch (NumberFormatException ignored) {
            // 留 0 → 用默认
        }
        return new InteractiveHitl(log, mode, sec > 0 ? Duration.ofSeconds(sec) : DEFAULT_TIMEOUT);
    }

    @Override
    public String id() {
        return "hitl.interactive(" + mode.name().toLowerCase() + ", timeout=" + timeout.toSeconds() + "s)";
    }

    public Mode mode() {
        return mode;
    }

    public Duration timeout() {
        return timeout;
    }

    // ------------------------------------------------------------------ 门控

    @Override
    public HitlDecision request(HitlRequest request) {
        String sessionId = request.sessionId();

        // 1) 先看会话级放行表：这是先前的 "always" 留下的
        if (allowlisted(sessionId, request.toolName())) {
            note(sessionId, request, "session-allowlist");
            return new HitlDecision.Once();
        }

        // 2) 模式。非 ASK 时压根不排队，但仍然留痕（见类注释的取舍 2）
        switch (mode) {
            case ALLOW -> {
                note(sessionId, request, "mode-allow");
                return new HitlDecision.Once();
            }
            case DENY -> {
                note(sessionId, request, "mode-deny");
                return new HitlDecision.Deny(
                        "approval is disabled by configuration (" + ENV_MODE + "=deny); "
                                + "no human is available to approve '" + request.toolName() + "'");
            }
            case ASK -> {
                // 继续往下排队
            }
        }

        // 本次请求可以自带等待上限（null = 用本实现的默认）。负数一律当"不等待"。
        Duration ttl = request.timeout() == null ? timeout : request.timeout();
        if (ttl.isNegative()) {
            ttl = Duration.ZERO;
        }

        if (ttl.isZero()) {
            // 显式要求"不等待"：留痕后直接超时。批处理与测试会这么用——
            // 它比"等 1 毫秒"诚实：pending 队列里不会出现一条注定没人答的请求。
            String requestId = nextId();
            log.append(sessionId, SessionLog.EV_HITL_REQUEST, requestPayload(requestId, request, ttl));
            log.append(sessionId, SessionLog.EV_HITL_RESOLVED,
                    resolvedPayload(requestId, request, "timeout", "zero-timeout budget"));
            return new HitlDecision.Timeout();
        }

        String requestId = nextId();
        Instant now = Instant.now();
        Waiting w = new Waiting(request, now, now.plus(ttl));
        waiting.put(requestId, w);
        log.append(sessionId, SessionLog.EV_HITL_REQUEST, requestPayload(requestId, request, ttl));

        try {
            return w.future.get(ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return giveUp(w, requestId, request, ttl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return giveUp(w, requestId, request, ttl);
        } catch (ExecutionException e) {
            // 只可能是 resolve 之类自己抛的；当成系统故障，按超时处理但不静默
            log.append(sessionId, SessionLog.EV_ERROR,
                    Map.of("step", "-", "error", "hitl wait failed: " + e.getCause()));
            return giveUp(w, requestId, request, ttl);
        }
    }

    /**
     * 超时/中断路径。
     *
     * <p>仲裁靠 {@link Waiting#decided} 这个 CAS，而不是靠 {@code future.complete}——
     * 因为**写日志必须早于唤醒等待者**：等待者一被唤醒就可能立刻去读会话日志
     * （审计、前端、以及那条"超时与回答撞在一起"的测试都会这么干）。
     * 如果拿 {@code complete} 当仲裁者，就只能"先 complete 再写日志"，
     * 于是出现"决定已生效、日志里却还没有"的窗口——审计最不该有的性质。
     *
     * <p>抢不到仲裁权时用 {@code join()} 等对方把日志写完再取结果，顺序因此恒定。
     */
    private HitlDecision giveUp(Waiting w, String requestId, HitlRequest request, Duration ttl) {
        if (!w.decided.compareAndSet(false, true)) {
            return w.future.join(); // 有人先答了，用他的答案
        }
        waiting.remove(requestId);
        log.append(request.sessionId(), SessionLog.EV_HITL_RESOLVED,
                resolvedPayload(requestId, request, "timeout",
                        "no human response within " + ttl.toSeconds() + "s"));
        w.future.complete(TIMED_OUT);
        return TIMED_OUT;
    }

    // ------------------------------------------------------------- 对外回答口

    /** 当前所有待回答的请求。供控制台/前端渲染。 */
    public List<PendingApproval> pending() {
        List<PendingApproval> out = new ArrayList<>();
        waiting.forEach((id, w) -> out.add(w.toView(id)));
        out.sort(Comparator.comparing(PendingApproval::requestedAt));
        return out;
    }

    public List<PendingApproval> pendingFor(String sessionId) {
        return pending().stream().filter(p -> p.sessionId().equals(sessionId)).toList();
    }

    public boolean isWaiting(String requestId) {
        return waiting.containsKey(requestId);
    }

    /**
     * 回答一条待确认请求。
     *
     * <p>{@code decision} 只接受人在界面上能做出的四种：{@code once} / {@code always} /
     * {@code deny} / {@code modified}。{@code timeout} <b>故意不开放</b>——
     * 那是"系统等不到人"的状态，客户端不该有权伪造它。
     *
     * @return false = 这条请求不存在、已被回答、或已超时
     */
    public boolean resolve(String requestId, HitlDecision decision) {
        if (requestId == null || decision == null) {
            return false;
        }
        if (decision instanceof HitlDecision.Timeout) {
            return false; // 见方法注释：不接受客户端伪造超时
        }
        Waiting w = waiting.get(requestId);
        if (w == null) {
            return false;
        }
        if (!w.decided.compareAndSet(false, true)) {
            return false; // 已超时（或重复提交）
        }
        waiting.remove(requestId);

        // 顺序有意为之：**先落事实、再改状态、最后才唤醒等待者**。
        // 等待者醒来后可能立刻读日志、也可能立刻再发一次同工具的调用（此时它必须
        // 已经命中放行表）——任何一步排在 complete 之后，都会留下一段"看起来没发生过"的窗口。
        log.append(w.request.sessionId(), SessionLog.EV_HITL_RESOLVED,
                resolvedPayload(requestId, w.request, kindOf(decision), detailOf(decision)));
        if (decision instanceof HitlDecision.Always) {
            sessionAllowlist
                    .computeIfAbsent(w.request.sessionId(), k -> ConcurrentHashMap.newKeySet())
                    .add(w.request.toolName());
        }
        w.future.complete(decision);
        return true;
    }

    /** 本会话已放行的工具（排查"为什么它没问我"用）。 */
    public Set<String> allowlistFor(String sessionId) {
        Set<String> s = sessionAllowlist.get(sessionId);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    private boolean allowlisted(String sessionId, String toolName) {
        Set<String> s = sessionAllowlist.get(sessionId);
        return s != null && s.contains(toolName);
    }

    // ------------------------------------------------------------------ 留痕

    private void note(String sessionId, HitlRequest request, String reason) {
        log.append(sessionId, SessionLog.EV_HITL_AUTO,
                Map.of("tool", nz(request.toolName()), "reason", reason));
    }

    private static Map<String, Object> requestPayload(String requestId, HitlRequest request, Duration ttl) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("requestId", requestId);
        p.put("tool", nz(request.toolName()));
        p.put("args", nz(request.argumentsJson()));
        p.put("reason", nz(request.reason()));
        p.put("timeoutSec", ttl.toSeconds());
        return p;
    }

    private static Map<String, Object> resolvedPayload(
            String requestId, HitlRequest request, String decision, String detail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("requestId", requestId);
        p.put("tool", nz(request.toolName()));
        p.put("decision", decision);
        p.put("detail", nz(detail));
        return p;
    }

    private String nextId() {
        return "h" + counter.incrementAndGet();
    }

    static String kindOf(HitlDecision d) {
        return switch (d) {
            case HitlDecision.Once ignored -> "once";
            case HitlDecision.Always ignored -> "always";
            case HitlDecision.Deny ignored -> "deny";
            case HitlDecision.Modified ignored -> "modified";
            case HitlDecision.Timeout ignored -> "timeout";
        };
    }

    static String detailOf(HitlDecision d) {
        return switch (d) {
            case HitlDecision.Deny deny -> nz(deny.reason());
            case HitlDecision.Modified m -> "args → " + nz(m.newArgumentsJson());
            default -> "";
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 一条在等的请求：挂起线程 + 供界面展示的快照。 */
    private static final class Waiting {
        final HitlRequest request;
        final Instant requestedAt;
        final Instant expiresAt;
        /** 谁先把它置为 true，谁负责"落事实 + 唤醒"——见 {@link #giveUp} 的顺序说明。 */
        final java.util.concurrent.atomic.AtomicBoolean decided =
                new java.util.concurrent.atomic.AtomicBoolean();
        final CompletableFuture<HitlDecision> future = new CompletableFuture<>();

        Waiting(HitlRequest request, Instant requestedAt, Instant expiresAt) {
            this.request = request;
            this.requestedAt = requestedAt;
            this.expiresAt = expiresAt;
        }

        PendingApproval toView(String requestId) {
            return new PendingApproval(requestId, request.sessionId(), nz(request.toolName()),
                    nz(request.argumentsJson()), nz(request.reason()), requestedAt, expiresAt);
        }
    }
}
