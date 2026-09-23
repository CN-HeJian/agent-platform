package com.aplat.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.HitlDecision;
import com.aplat.seam.HitlRequest;
import com.aplat.seam.SessionLog;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U12 验收：once / always / deny / modify / timeout 五条路径各自可独立验证。
 *
 * <p>这些用例刻意不 {@code sleep(500)} 等结果——等待与超时都靠"轮询 pending 队列"和
 * "显式零超时"来取得确定性。时间敏感的测试一旦靠 sleep，就会在 CI 上随机红。
 */
class InteractiveHitlTest {

    private final SessionLog log = new EventSourcedSessionLog(new InMemoryStore());

    private InteractiveHitl hitl(Duration timeout) {
        return new InteractiveHitl(log, InteractiveHitl.Mode.ASK, timeout);
    }

    private static HitlRequest req(String sessionId, String tool, String args) {
        return new HitlRequest(sessionId, tool, args, "该工具会执行命令", null);
    }

    private int count(String sessionId, String type) {
        return ((EventSourcedSessionLog) log).ofType(sessionId, type).size();
    }

    @Test
    @DisplayName("构造处的零与 null 是两个意思：零 = 不等待，null = 用默认（同一层里不能有两个零）")
    void zeroMeansDoNotWaitAndNullMeansDefault() {
        assertEquals(InteractiveHitl.DEFAULT_TIMEOUT,
                new InteractiveHitl(log, InteractiveHitl.Mode.ASK, null).timeout(),
                "null 才是「没配，用默认」");
        assertTrue(new InteractiveHitl(log, InteractiveHitl.Mode.ASK, Duration.ZERO).timeout().isZero(),
                "零表示不等待。它曾经被解释成「用默认」，于是读 seam 文档写 Duration.ZERO 的人"
                        + "会莫名其妙等满 120 秒");
        assertTrue(new InteractiveHitl(log, InteractiveHitl.Mode.ASK, Duration.ofSeconds(-3))
                .timeout().isZero(), "负数同样当「不等待」，不抛");

        long t0 = System.nanoTime();
        HitlDecision d = hitl(Duration.ZERO).request(req("s1", "shell", "{}"));
        assertInstanceOf(HitlDecision.Timeout.class, d);
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertTrue(ms < 1000, "「不等待」就该立刻返回，实际等了 " + ms + "ms");

        // 但不等待 ≠ 不留痕：requested/resolved 一对仍然要落，否则审计里出现无法解释的空档
        assertEquals(1, count("s1", SessionLog.EV_HITL_REQUEST));
        assertEquals(1, count("s1", SessionLog.EV_HITL_RESOLVED));
        assertEquals(0, hitl(Duration.ZERO).pending().size(), "不等待的请求不该进 pending 队列");
    }

    /** 等它真的进队列——用轮询而不是 sleep 猜时间。 */
    private static void awaitPending(InteractiveHitl h, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && h.pending().size() != expected) {
            Thread.sleep(2);
        }
        assertEquals(expected, h.pending().size(), "等待中的请求数不符");
    }

    // ---------------------------------------------------------------- once

    @Test
    @DisplayName("ASK：请求真的挂住调用线程，直到有人回答才放行")
    void askBlocksUntilAnswered() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{\"command\":\"ls\"}")));

            awaitPending(h, 1);
            PendingApproval p = h.pending().get(0);
            assertEquals("shell", p.toolName());
            assertEquals("s1", p.sessionId());
            assertTrue(p.argumentsJson().contains("ls"), "参数要原样交给审批人看");
            assertTrue(p.remainingMillis() > 0);

            assertTrue(h.resolve(p.requestId(), new HitlDecision.Once()));
            assertInstanceOf(HitlDecision.Once.class, f.get(3, TimeUnit.SECONDS));
            assertTrue(h.pending().isEmpty(), "答过之后不该还留在队列里");
        }
    }

    @Test
    @DisplayName("全过程留痕：requested / resolved 各一条，回放里能看出是谁怎么答的")
    void bothSidesAreLogged() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{\"command\":\"ls\"}")));
            awaitPending(h, 1);
            h.resolve(h.pending().get(0).requestId(), new HitlDecision.Once());
            f.get(3, TimeUnit.SECONDS);
        }

        assertEquals(1, count("s1", SessionLog.EV_HITL_REQUEST));
        assertEquals(1, count("s1", SessionLog.EV_HITL_RESOLVED));

        var requested = ((EventSourcedSessionLog) log).ofType("s1", SessionLog.EV_HITL_REQUEST).get(0);
        assertNotNull(requested.get("requestId"));
        assertEquals("shell", requested.str("tool"));
        assertEquals("once",
                ((EventSourcedSessionLog) log).ofType("s1", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
    }

    // --------------------------------------------------------------- always

    @Test
    @DisplayName("always 落到会话放行表：同会话同工具不再问第二遍，且记一条 auto_approved")
    void alwaysIsRememberedPerSession() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> first = exec.submit(() -> h.request(req("s1", "shell", "{}")));
            awaitPending(h, 1);
            assertTrue(h.resolve(h.pending().get(0).requestId(), new HitlDecision.Always()));
            assertInstanceOf(HitlDecision.Always.class, first.get(3, TimeUnit.SECONDS));
        }
        assertEquals(Set.of("shell"), h.allowlistFor("s1"));

        // 第二次：不排队，立刻返回
        HitlDecision second = h.request(req("s1", "shell", "{}"));
        assertInstanceOf(HitlDecision.Once.class, second, "已放行的工具不该再问一遍");
        assertTrue(h.pending().isEmpty());

        // 但"没问就放行"必须留痕，否则回放里会出现无法解释的空白
        assertEquals(1, count("s1", SessionLog.EV_HITL_AUTO));

        // 另一个工具仍要问
        assertFalse(h.allowlistFor("s1").contains("other_tool"));
    }

    @Test
    @DisplayName("放行表不跨会话：另一个会话里的同一个工具照样要问")
    void allowlistDoesNotLeakAcrossSessions() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{}")));
            awaitPending(h, 1);
            h.resolve(h.pending().get(0).requestId(), new HitlDecision.Always());
            f.get(3, TimeUnit.SECONDS);

            // s2 第一次调用：必须仍然排队等人
            Future<HitlDecision> other = exec.submit(() -> h.request(req("s2", "shell", "{}")));
            awaitPending(h, 1);
            assertEquals("s2", h.pending().get(0).sessionId());
            assertEquals(Set.of(), h.allowlistFor("s2"));

            h.resolve(h.pending().get(0).requestId(), new HitlDecision.Once());
            other.get(3, TimeUnit.SECONDS);
        }
    }

    // ----------------------------------------------------------------- deny

    @Test
    @DisplayName("deny：理由原样带回，模型据此改道（而不是重试同一个调用）")
    void denyCarriesReason() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{}")));
            awaitPending(h, 1);
            h.resolve(h.pending().get(0).requestId(), new HitlDecision.Deny("这条命令会删东西"));

            HitlDecision d = f.get(3, TimeUnit.SECONDS);
            assertInstanceOf(HitlDecision.Deny.class, d);
            assertEquals("这条命令会删东西", ((HitlDecision.Deny) d).reason());
        }
        assertEquals("deny",
                ((EventSourcedSessionLog) log).ofType("s1", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
    }

    // ------------------------------------------------------------- modified

    @Test
    @DisplayName("modify：改后的参数原样交给管线，并在 resolved 事件里留下改动")
    void modifiedArgsAreDeliveredAndLogged() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        String newArgs = "{\"command\":\"ls -la\"}";
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{\"command\":\"rm -rf /\"}")));
            awaitPending(h, 1);
            h.resolve(h.pending().get(0).requestId(), new HitlDecision.Modified(newArgs));

            HitlDecision d = f.get(3, TimeUnit.SECONDS);
            assertInstanceOf(HitlDecision.Modified.class, d);
            assertEquals(newArgs, ((HitlDecision.Modified) d).newArgumentsJson());
        }
        var resolved = ((EventSourcedSessionLog) log).ofType("s1", SessionLog.EV_HITL_RESOLVED).get(0);
        assertEquals("modified", resolved.str("decision"));
        assertTrue(resolved.str("detail").contains("ls -la"), "改了参数却不记下来，事后没法解释为什么执行了这条");
    }

    // -------------------------------------------------------------- timeout

    @Test
    @DisplayName("零超时 = 不等待：直接超时，且队列里不留注定没人答的请求")
    void zeroTimeoutDoesNotQueue() {
        InteractiveHitl h = hitl(Duration.ofSeconds(30));
        HitlDecision d = h.request(new HitlRequest("s1", "shell", "{}", "r", Duration.ZERO));

        assertInstanceOf(HitlDecision.Timeout.class, d);
        assertTrue(h.pending().isEmpty(), "零超时的请求不该进待办队列");
        // 仍然两侧留痕：否则回放里看不出"这里本来要问人"
        assertEquals(1, count("s1", SessionLog.EV_HITL_REQUEST));
        assertEquals("timeout",
                ((EventSourcedSessionLog) log).ofType("s1", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
    }

    @Test
    @DisplayName("真的等不到人：超时返回 Timeout，此后提交决定返回 false（已过期）")
    void expiredRequestCannotBeAnswered() {
        InteractiveHitl h = hitl(Duration.ofMillis(120));
        HitlDecision d = h.request(req("s1", "shell", "{}"));

        assertInstanceOf(HitlDecision.Timeout.class, d);
        assertTrue(h.pending().isEmpty());
        assertEquals("timeout",
                ((EventSourcedSessionLog) log).ofType("s1", SessionLog.EV_HITL_RESOLVED).get(0).str("decision"));
        assertFalse(h.resolve("h1", new HitlDecision.Once()), "过期的请求不能再被回答");
    }

    // ---------------------------------------------------- 回答口本身的规则

    @Test
    @DisplayName("客户端不能伪造 timeout（那是「系统等不到人」的状态，不是一种回答）")
    void clientCannotForgeTimeout() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{}")));
            awaitPending(h, 1);
            String id = h.pending().get(0).requestId();

            assertFalse(h.resolve(id, new HitlDecision.Timeout()), "不该接受伪造的超时");
            assertTrue(h.isWaiting(id), "请求应当仍在等待");

            h.resolve(id, new HitlDecision.Once());
            f.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("重复提交只认第一次：第二次返回 false，resolved 事件也只有一条")
    void secondResolveIsRejected() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HitlDecision> f = exec.submit(() -> h.request(req("s1", "shell", "{}")));
            awaitPending(h, 1);
            String id = h.pending().get(0).requestId();

            assertTrue(h.resolve(id, new HitlDecision.Once()));
            assertFalse(h.resolve(id, new HitlDecision.Deny("改主意了")), "第二次不该生效");
            f.get(3, TimeUnit.SECONDS);
        }
        assertEquals(1, count("s1", SessionLog.EV_HITL_RESOLVED));
        assertFalse(hitl(Duration.ofSeconds(1)).resolve("no-such-id", new HitlDecision.Once()));
    }

    @Test
    @DisplayName("超时与回答撞在同一刻：只有一个赢家，resolved 恰好一条")
    void timeoutAndAnswerRaceHasOneWinner() throws Exception {
        // 反复跑这个窄窗口：不变量是"恰一条 resolved"，胜负本身不固定
        for (int i = 0; i < 40; i++) {
            SessionLog l = new EventSourcedSessionLog(new InMemoryStore());
            InteractiveHitl h = new InteractiveHitl(l, InteractiveHitl.Mode.ASK, Duration.ofMillis(1));
            String sid = "race" + i;

            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<HitlDecision> f = exec.submit(() ->
                        h.request(new HitlRequest(sid, "shell", "{}", "r", Duration.ofMillis(1))));
                // 同一时刻从另一个线程抢答
                exec.submit(() -> h.resolve("h1", new HitlDecision.Once()));

                HitlDecision d = f.get(3, TimeUnit.SECONDS);
                assertTrue(d instanceof HitlDecision.Once || d instanceof HitlDecision.Timeout,
                        "只可能是「人答了」或「超时」，实际: " + d);
                assertEquals(1, ((EventSourcedSessionLog) l).ofType(sid, SessionLog.EV_HITL_RESOLVED).size(),
                        "两侧都写 resolved 会让审计出现两条互相矛盾的事实");
            }
        }
    }

    // ---------------------------------------------------------------- 模式

    @Test
    @DisplayName("ALLOW / DENY 模式不排队，但都会留一条 auto_approved")
    void nonAskModesDoNotQueueAndStillLeaveATrace() {
        SessionLog l1 = new EventSourcedSessionLog(new InMemoryStore());
        InteractiveHitl allow = new InteractiveHitl(l1, InteractiveHitl.Mode.ALLOW, Duration.ofSeconds(5));
        assertInstanceOf(HitlDecision.Once.class, allow.request(req("s1", "shell", "{}")));
        assertTrue(allow.pending().isEmpty());
        assertEquals(1, ((EventSourcedSessionLog) l1).ofType("s1", SessionLog.EV_HITL_AUTO).size());

        SessionLog l2 = new EventSourcedSessionLog(new InMemoryStore());
        InteractiveHitl deny = new InteractiveHitl(l2, InteractiveHitl.Mode.DENY, Duration.ofSeconds(5));
        HitlDecision d = deny.request(req("s1", "shell", "{}"));
        assertInstanceOf(HitlDecision.Deny.class, d);
        assertTrue(((HitlDecision.Deny) d).reason().contains("deny"), "理由要指向配置，便于排查");
        assertEquals(1, ((EventSourcedSessionLog) l2).ofType("s1", SessionLog.EV_HITL_AUTO).size());
    }

    @Test
    @DisplayName("配置解析：默认 ask；非法值退回默认，而不是静默变成「放行」")
    void modeParsingFailsSafe() {
        assertEquals(InteractiveHitl.Mode.ASK, InteractiveHitl.Mode.parse(null, InteractiveHitl.Mode.ASK));
        assertEquals(InteractiveHitl.Mode.ASK, InteractiveHitl.Mode.parse("garbage", InteractiveHitl.Mode.ASK));
        assertEquals(InteractiveHitl.Mode.ALLOW, InteractiveHitl.Mode.parse("off", InteractiveHitl.Mode.ASK));
        assertEquals(InteractiveHitl.Mode.ASK, InteractiveHitl.Mode.parse("on", InteractiveHitl.Mode.DENY));

        assertEquals(InteractiveHitl.Mode.ALLOW,
                InteractiveHitl.fromEnv(log, Map.of("APLAT_HITL", "allow")).mode());
        assertEquals(300,
                InteractiveHitl.fromEnv(log, Map.of("APLAT_HITL_TIMEOUT_SEC", "300")).timeout().toSeconds());
        // 坏值退回默认，而不是变成 0（0 会被理解成"不等待"——那是完全相反的行为）
        assertEquals(InteractiveHitl.DEFAULT_TIMEOUT,
                InteractiveHitl.fromEnv(log, Map.of("APLAT_HITL_TIMEOUT_SEC", "abc")).timeout());
    }

    @Test
    @DisplayName("pending 可按会话过滤，整体按请求时间排序（界面按等待时间排优先级）")
    void pendingIsFilterableAndOrdered() throws Exception {
        InteractiveHitl h = hitl(Duration.ofSeconds(10));
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String sid : List.of("s1", "s2", "s1")) {
                exec.submit(() -> h.request(req(sid, "shell", "{}")));
            }
            awaitPending(h, 3);
            assertEquals(2, h.pendingFor("s1").size());
            assertEquals(1, h.pendingFor("s2").size());

            List<PendingApproval> all = h.pending();
            for (int i = 1; i < all.size(); i++) {
                assertFalse(all.get(i).requestedAt().isBefore(all.get(i - 1).requestedAt()),
                        "待办必须按时间升序");
            }
        }
    }
}
