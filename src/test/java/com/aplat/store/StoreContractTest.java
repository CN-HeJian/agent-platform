package com.aplat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Store 契约测试。
 *
 * <p>这个测试类的**真正用途**是：日后写 MySQL 实现时，让它继承同一套断言。
 * 内存实现只是"契约的参照物"，不是被测对象。
 */
class StoreContractTest {

    private final InMemoryStore store = new InMemoryStore();

    private long append(String sessionId, String type) {
        return store.append(new SessionEvent(sessionId, 0, type, Map.of("t", type), null));
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
        assertEquals(2, store.events("s1", 1).size());
        assertEquals(0, store.events("s1", 3).size());
    }

    @Test
    @DisplayName("会话之间相互隔离")
    void sessionsAreIsolated() {
        append("s1", "a");
        append("s2", "a");
        append("s2", "b");
        assertEquals(1, store.events("s1", 0).size());
        assertEquals(2, store.events("s2", 0).size());
    }

    @Test
    @DisplayName("快照读写：崩溃后从最近检查点续跑的依据")
    void snapshotRoundTrip() {
        store.saveSnapshot(new Store.Snapshot("s1", "step-3", "{\"step\":3}", null));
        assertTrue(store.latestSnapshot("s1").isPresent());
        assertEquals("step-3", store.latestSnapshot("s1").get().stepId());
    }

    @Test
    @DisplayName("幂等键：首次见到为 true，重复为 false")
    void idempotencyKeySemantics() {
        assertTrue(store.markIfAbsent("k1", "ref-1"));
        assertFalse(store.markIfAbsent("k1", "ref-2"), "重复键必须返回 false");
        assertEquals("ref-1", store.idempotentRef("k1").orElseThrow(), "首次写入的引用不能被覆盖");
    }

    @Test
    @DisplayName("clear() 让测试之间互不污染")
    void clearResetsEverything() {
        append("s1", "a");
        store.markIfAbsent("k", "v");
        store.clear();
        assertEquals(0, store.events("s1", 0).size());
        assertTrue(store.idempotentRef("k").isEmpty());
        assertEquals(1, append("s1", "a"), "清空后 seq 重新从 1 开始");
    }
}
