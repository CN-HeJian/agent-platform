package com.aplat.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.ToolResult;
import com.aplat.store.InMemoryStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U21 验收：幂等闸的三段式，三种崩点各自可独立验证。
 *
 * <p>这个类的价值在于它把"副作用至多一次"拆成了**可断言的三条路径**。
 * 在此之前只能说"续跑应该不会重复执行吧"——那种说法没法测，也就没法保证。
 */
class StoreIdempotencyTest {

    private final InMemoryStore store = new InMemoryStore();
    private StoreIdempotency guard;

    @BeforeEach
    void setUp() {
        store.clear();
        guard = new StoreIdempotency(store);
    }

    @Test
    @DisplayName("崩在 claim 之前：什么都没发生过，正常执行")
    void firstClaimGoesThrough() {
        assertTrue(guard.claim("k1").isEmpty(), "第一次必须放行去执行");
    }

    @Test
    @DisplayName("崩在 claim 与 complete 之间：结果未知，拦下来而不是重跑")
    void crashBetweenClaimAndCompleteSuppresses() {
        assertTrue(guard.claim("k1").isEmpty());     // 声明了
        // ……然后进程没了，complete 从未被调用

        Optional<ToolResult> second = guard.claim("k1");
        assertTrue(second.isPresent(), "第二次必须被拦下");
        assertFalse(second.get().ok());
        assertEquals(IdempotencyGuard.ERR_DUPLICATE_SUPPRESSED, second.get().errorCode());
        assertTrue(second.get().content().contains("may or may not have taken effect"),
                "给模型的措辞必须说清「可能已经生效」，否则它会当成普通失败去重试: "
                        + second.get().content());
    }

    @Test
    @DisplayName("崩在 complete 之后：直接回放上次的结果，不执行第二次")
    void crashAfterCompleteReplaysResult() {
        assertTrue(guard.claim("k1").isEmpty());
        guard.complete("k1", ToolResult.ok("命令已执行，输出 42"));

        Optional<ToolResult> replayed = guard.claim("k1");
        assertTrue(replayed.isPresent());
        assertTrue(replayed.get().ok(), "成功的结果要原样回放");
        assertEquals("命令已执行，输出 42", replayed.get().content());
    }

    @Test
    @DisplayName("失败的结果也回放——否则模型每次续跑都会重试同一个必然失败的调用")
    void failedResultIsReplayedToo() {
        assertTrue(guard.claim("k1").isEmpty());
        guard.complete("k1", ToolResult.error(ToolResult.ERR_BLOCKED, "策略拦截"));

        Optional<ToolResult> replayed = guard.claim("k1");
        assertTrue(replayed.isPresent());
        assertFalse(replayed.get().ok());
        assertEquals(ToolResult.ERR_BLOCKED, replayed.get().errorCode());
    }

    @Test
    @DisplayName("读不懂的旧结果也不重跑——宁可让模型多问一句")
    void unreadableResultStillSuppresses() {
        // 直接用 markIfAbsent 塞一个不是合法 JSON 的引用，模拟格式变更后留下的旧数据。
        // 首次声明返回 true（这就是"键刚被建出来"的含义，不是"出错了"）。
        assertTrue(store.markIfAbsent("tool:k1", "{这不是 JSON"), "首次声明必须成功");

        Optional<ToolResult> result = guard.claim("k1");
        assertTrue(result.isPresent(), "读不懂也必须拦，绝不能退回「没见过来执行」");
        assertEquals(IdempotencyGuard.ERR_DUPLICATE_SUPPRESSED, result.get().errorCode());
        assertTrue(result.get().content().contains("unreadable"), result.get().content());
    }

    @Test
    @DisplayName("不同键互不影响：同一步里的两次不同调用各自独立")
    void keysAreIndependent() {
        assertTrue(guard.claim("a#1#c1").isEmpty(), "第一次先声明");
        guard.complete("a#1#c1", ToolResult.ok("first"));

        assertTrue(guard.claim("a#1#c2").isEmpty(), "另一个调用不该被前一个的结果顶掉");
        assertEquals("first", guard.claim("a#1#c1").orElseThrow().content());
    }

    @Test
    @DisplayName("没声明过就 complete 是空操作——不能凭空造出一条幂等键")
    void completeWithoutClaimIsNoop() {
        guard.complete("never-claimed", ToolResult.ok("不该被记住"));

        // 这一条钉住的是"授权必须来自 claim"：若 complete 能建键，
        // 那么任何一次键名拼错的回填都会让下一步误以为"这个副作用已经发生过了"。
        assertTrue(guard.claim("never-claimed").isEmpty(),
                "complete 不能把没声明过的键变成已声明");
    }
}
