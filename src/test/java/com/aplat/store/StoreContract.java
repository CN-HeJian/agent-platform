package com.aplat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Store 契约（U18）。
 *
 * <p>这是**抽象基类，不是被测对象**：内存实现与 JDBC 实现都继承它，跑同一套断言。
 * 这才是"可替换"的证明方式——不是"我写了个 MySQL 实现"，而是"两个实现在同一份契约下
 * 都通过"。加第三个实现（Redis / Postgres）时也不该另写一套测试。
 *
 * <p>刻意放进契约里的两类断言，因为它们最容易在换实现时被破坏：
 * <ul>
 *   <li><b>并发写不丢不重</b>——内存实现靠锁，JDBC 实现靠数据库的原子取号，
 *       两种机制完全不同，但对外表现必须一致；</li>
 *   <li><b>持久化实现之间可移植</b>——所有断言只用 {@link Store} 的公开方法，
 *       不碰任何实现细节（所以这里不 import InMemoryStore，也不看表名）。</li>
 * </ul>
 */
abstract class StoreContract {

    /** 被测实现。 */
    protected abstract Store store();

    /** 每个用例前清空——契约里必须包含"怎么重置"，否则用例之间会互相污染。 */
    protected abstract void reset();

    @BeforeEach
    void resetBeforeEach() {
        reset();
    }

    private long append(String sessionId, String type) {
        return store().append(new SessionEvent(sessionId, 0, type, Map.of("t", type), null));
    }

    @Test
    @DisplayName("append 返回单调递增 seq，且从 1 开始")
    void appendAssignsMonotonicSeq() {
        assertEquals(1, append("s1", "a"));
        assertEquals(2, append("s1", "b"));
        assertEquals(3, append("s1", "c"));
    }

    @Test
    @DisplayName("events(afterSeq) 只返回增量，支持断线续传")
    void eventsAfterSeq() {
        append("s1", "a");
        append("s1", "b");
        append("s1", "c");
        assertEquals(2, store().events("s1", 1).size());
        assertEquals(0, store().events("s1", 3).size());
    }

    @Test
    @DisplayName("会话之间相互隔离")
    void sessionsAreIsolated() {
        append("s1", "a");
        append("s2", "a");
        append("s2", "b");
        assertEquals(1, store().events("s1", 0).size());
        assertEquals(2, store().events("s2", 0).size());
    }

    @Test
    @DisplayName("载荷与时间戳原样往返——回放能复现当时模型看到的东西")
    void payloadAndTimestampRoundTrip() {
        java.time.Instant ts = java.time.Instant.ofEpochMilli(1_700_000_000_123L);
        store().append(new SessionEvent("s1", 0, "tool.result",
                // 刻意带上 null 值：成功路径上的 errorCode 就是 null，
                // 而 Map.copyOf 会拒绝它（这个坑本项目踩过一次）
                mapWithNull(), ts));

        List<SessionEvent> back = store().events("s1", 0);
        assertEquals(1, back.size());
        SessionEvent e = back.get(0);
        assertEquals("tool.result", e.type());
        assertEquals(1_700_000_000_123L, e.ts().toEpochMilli(), "时间戳必须精确到毫秒地往返");
        assertEquals("shell", e.str("tool"));
        assertTrue(e.payload().containsKey("errorCode"), "null 值字段不能丢");
        assertEquals(null, e.get("errorCode"));
        assertEquals(42, ((Number) e.get("size")).intValue(), "嵌套数字不能被转成字符串");
    }

    private static Map<String, Object> mapWithNull() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tool", "shell");
        m.put("errorCode", null);
        m.put("size", 42);
        return m;
    }

    @Test
    @DisplayName("快照读写：崩溃后从最近检查点续跑的依据")
    void snapshotRoundTrip() {
        store().saveSnapshot(new Store.Snapshot("s1", "step-3", "{\"step\":3}", null));
        assertTrue(store().latestSnapshot("s1").isPresent());
        assertEquals("step-3", store().latestSnapshot("s1").get().stepId());

        // 同一个会话的第二次检查点必须覆盖前一个，而不是插出两行
        store().saveSnapshot(new Store.Snapshot("s1", "step-5", "{\"step\":5}", null));
        assertEquals("step-5", store().latestSnapshot("s1").get().stepId());
        assertEquals("{\"step\":5}", store().latestSnapshot("s1").get().stateJson());
    }

    @Test
    @DisplayName("幂等键：首次见到为 true，重复为 false")
    void idempotencyKeySemantics() {
        assertTrue(store().markIfAbsent("k1", "ref-1"));
        assertFalse(store().markIfAbsent("k1", "ref-2"), "重复键必须返回 false");
        assertEquals("ref-1", store().idempotentRef("k1").orElseThrow(), "首次写入的引用不能被覆盖");
        assertTrue(store().idempotentRef("nope").isEmpty());
    }

    @Test
    @DisplayName("清空后 seq 重新从 1 开始（测试隔离的可信度就靠这条）")
    void clearResetsSeq() {
        append("s1", "a");
        store().markIfAbsent("k", "v");
        reset();
        assertEquals(0, store().events("s1", 0).size());
        assertTrue(store().idempotentRef("k").isEmpty());
        assertEquals(1, append("s1", "a"), "清空后 seq 重新从 1 开始");
    }

    @Test
    @DisplayName("并发写不丢不重：多线程追加同一会话，seq 恰好是 1..N 且无重复")
    void concurrentAppendsLoseNothing() throws Exception {
        final int threads = 8;
        final int perThread = 40;
        final int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    for (int i = 0; i < perThread; i++) {
                        store().append(new SessionEvent("hot", 0, "e" + tid,
                                Map.of("t", tid, "i", i), null));
                    }
                    return null;
                }));
            }
            start.countDown(); // 一起冲，尽量制造真实竞争
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<SessionEvent> all = store().events("hot", 0);
        assertEquals(total, all.size(), "一条都不能丢");

        Set<Long> seqs = new HashSet<>();
        long expected = 1;
        for (SessionEvent e : all) {
            assertTrue(seqs.add(e.seq()), "seq 重复了: " + e.seq());
            assertEquals(expected, e.seq(), "seq 必须连续无空洞（读回来是按 seq 排序的）");
            expected++;
        }
        assertEquals(total, seqs.size());
        assertNotEquals(0, seqs.size());
    }

    @Test
    @DisplayName("并发首写同一个新会话：也不会撞号（这是取号实现最容易翻车的地方）")
    void concurrentFirstAppendOfNewSession() throws Exception {
        final int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return store().append(new SessionEvent("brand-new", 0, "first",
                            Map.of(), null));
                }));
            }
            start.countDown();

            Set<Long> seqs = new HashSet<>();
            for (Future<Long> f : futures) {
                seqs.add(f.get(60, TimeUnit.SECONDS));
            }
            assertEquals(threads, seqs.size(), "并发首写的序号不能重复: " + seqs);
        } finally {
            pool.shutdownNow();
        }
    }
}
