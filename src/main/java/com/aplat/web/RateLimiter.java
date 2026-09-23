package com.aplat.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流（U17），按调用方隔离。
 *
 * <p>为什么是令牌桶而不是固定窗口：固定窗口在窗口边界会放行两倍流量
 * （第 1 秒末尾一批 + 第 2 秒开头一批）。令牌桶按时间连续补充，边界平滑，
 * 而且天然给出"还要等多久"——可以直接填 {@code Retry-After}。
 *
 * <p>时钟可注入，所以"桶怎么补、补到什么时候能再放一个"能被确定性地测出来，
 * 不用在测试里 {@code sleep}。
 *
 * <p>不做的事：不区分接口权重（SSE 长连接与一次 /health 同样算一个令牌），
 * 不做分布式（多实例部署时每个实例各限各的）。这两条都属"知道了再改"的范围。
 */
public final class RateLimiter {

    /** 纳秒时钟。抽出来是为了让测试不用等真实时间。 */
    @FunctionalInterface
    public interface Clock {
        long nanoTime();
    }

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    /** 身份表上限：超过就开始清理"已补满"的桶，避免被伪造身份撑爆内存。 */
    private static final int MAX_IDENTITIES = 1_024;

    private final int permitsPerMinute;
    private final double permitsPerNano;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(int permitsPerMinute) {
        this(permitsPerMinute, System::nanoTime);
    }

    public RateLimiter(int permitsPerMinute, Clock clock) {
        this.permitsPerMinute = Math.max(0, permitsPerMinute);
        this.permitsPerNano = this.permitsPerMinute / (double) NANOS_PER_MINUTE;
        this.clock = clock;
    }

    public boolean enabled() {
        return permitsPerMinute > 0;
    }

    public int permitsPerMinute() {
        return permitsPerMinute;
    }

    /**
     * 尝试取一个令牌。
     *
     * @return true = 放行；false = 该调用方已超限
     */
    public boolean tryAcquire(String identity) {
        if (!enabled()) {
            return true;
        }
        if (buckets.size() > MAX_IDENTITIES) {
            pruneFullyRefilled();
        }
        Bucket bucket = buckets.computeIfAbsent(key(identity), k -> new Bucket(permitsPerMinute, clock.nanoTime()));
        return bucket.tryAcquire(clock.nanoTime());
    }

    /**
     * 还要等几秒才能再放一个（用于 {@code Retry-After}）。
     *
     * @return 0 = 现在就能放
     */
    public long retryAfterSeconds(String identity) {
        if (!enabled()) {
            return 0;
        }
        Bucket bucket = buckets.get(key(identity));
        if (bucket == null) {
            return 0;
        }
        return bucket.retryAfterSeconds(clock.nanoTime(), permitsPerNano);
    }

    /** 当前跟踪的调用方数量（排查用）。 */
    public int trackedIdentities() {
        return buckets.size();
    }

    /** 清掉已经补满的桶——它们等价于"这个调用方很久没来了"。 */
    private void pruneFullyRefilled() {
        buckets.entrySet().removeIf(e -> e.getValue().isFullyRefilled());
    }

    private static String key(String identity) {
        return identity == null || identity.isBlank() ? ApiKeyGuard.ANONYMOUS : identity;
    }

    private static final class Bucket {

        private final int capacity;
        private double tokens;
        private long lastNanos;

        Bucket(int capacity, long nowNanos) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastNanos = nowNanos;
        }

        boolean tryAcquire(long nowNanos) {
            refill(nowNanos);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        long retryAfterSeconds(long nowNanos, double permitsPerNano) {
            refill(nowNanos);
            if (tokens >= 1.0 || permitsPerNano <= 0) {
                return 0;
            }
            double needed = 1.0 - tokens;
            // 向上取整：宁可让调用方多等一秒，也不要它立刻再来撞一次
            return (long) Math.ceil(needed / (permitsPerNano * 1_000_000_000L));
        }

        boolean isFullyRefilled() {
            return tokens >= capacity;
        }

        private void refill(long nowNanos) {
            long elapsed = nowNanos - lastNanos;
            if (elapsed <= 0) {
                return;
            }
            // 上限按"桶容量"而不是"补满需要的时间"——容量就是每分钟额度
            tokens = Math.min(capacity, tokens + elapsed * (capacity / (double) NANOS_PER_MINUTE));
            lastNanos = nowNanos;
        }
    }
}
