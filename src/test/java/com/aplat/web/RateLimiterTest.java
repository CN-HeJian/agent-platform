package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U17 限流的确定性验证。
 *
 * <p>时钟是注入的，所以"桶怎么补、补到什么时候能再放一个、Retry-After 报多少"
 * 全都能精确断言——不用在测试里 sleep，也就不会因为机器慢而偶发失败。
 */
class RateLimiterTest {

    /** 手摇时钟：测试自己想"过多久"就过多久。 */
    private static final class FakeClock implements RateLimiter.Clock {
        private long now;

        @Override
        public long nanoTime() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now += seconds * 1_000_000_000L;
        }

        void advanceMillis(long millis) {
            now += millis * 1_000_000L;
        }
    }

    @Test
    @DisplayName("关闭限流（0）时一律放行")
    void disabledAllowsEverything() {
        RateLimiter limiter = new RateLimiter(0, new FakeClock());

        assertFalse(limiter.enabled());
        for (int i = 0; i < 1_000; i++) {
            assertTrue(limiter.tryAcquire("anyone"));
        }
    }

    @Test
    @DisplayName("一分钟内的额度用满即拒，第 N+1 个请求被挡")
    void allowsUpToCapacityThenDenies() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(5, clock);

        for (int i = 1; i <= 5; i++) {
            assertTrue(limiter.tryAcquire("k1"), "第 " + i + " 个应当放行");
        }
        assertFalse(limiter.tryAcquire("k1"), "第 6 个应当被挡");
    }

    @Test
    @DisplayName("按时间连续补充：60/分钟 ≈ 每秒补 1 个")
    void refillsOverTime() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(60, clock);

        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("k1");
        }
        assertFalse(limiter.tryAcquire("k1"), "此刻应当是空的");

        clock.advanceSeconds(1);
        assertTrue(limiter.tryAcquire("k1"), "过 1 秒应恰好补出 1 个");

        assertFalse(limiter.tryAcquire("k1"), "只补了 1 个，第二个还得等");
    }

    @Test
    @DisplayName("额度不会超补：闲置 1 小时后仍然只有容量那么多")
    void doesNotOverfill() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(3, clock);

        clock.advanceSeconds(3_600);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("k1"), "第 " + (i + 1) + " 个应放行");
        }
        assertFalse(limiter.tryAcquire("k1"), "上限就是 3，长时间闲置也不该攒出更多");
    }

    @Test
    @DisplayName("Retry-After 向上取整——宁可让调用方多等一秒，也别让它立刻再撞")
    void retryAfterIsRoundedUp() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(60, clock); // 1 个/秒

        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("k1");
        }
        assertEquals(1, limiter.retryAfterSeconds("k1"), "需要 1 个令牌 = 1 秒");

        // 补出 0.5 个：还差 0.5 个，按秒向上取整仍是 1
        clock.advanceMillis(500);
        assertEquals(1, limiter.retryAfterSeconds("k1"));

        clock.advanceMillis(600);
        assertEquals(0, limiter.retryAfterSeconds("k1"), "已经够一个了，不该再让等");
    }

    @Test
    @DisplayName("按调用方隔离：一个人刷满不影响别人")
    void identitiesAreIsolated() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(2, clock);

        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"), "a 已用满");

        assertTrue(limiter.tryAcquire("b"), "b 不该受 a 影响");
        assertTrue(limiter.tryAcquire("b"));
        assertFalse(limiter.tryAcquire("b"));

        assertEquals(2, limiter.trackedIdentities());
    }

    @Test
    @DisplayName("空身份归到 anonymous 桶，不会 NPE 也不会各算各的")
    void blankIdentityUsesAnonymousBucket() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(1, clock);

        assertTrue(limiter.tryAcquire(null));
        assertFalse(limiter.tryAcquire("  "), "空身份应落在同一个桶里");
        assertEquals(1, limiter.trackedIdentities());
    }

    @Test
    @DisplayName("未见过的调用方 retryAfter 为 0（不该凭空空等）")
    void unknownIdentityHasNoDelay() {
        RateLimiter limiter = new RateLimiter(10, new FakeClock());

        assertEquals(0, limiter.retryAfterSeconds("never-seen"));
    }
}
