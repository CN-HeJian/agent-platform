package com.aplat.tenant;

/**
 * 租户上下文（U33）。
 *
 * <p>与 {@code ScopedHitl} 用同一个模式：一个 ThreadLocal，由传输层在请求开始时设置、
 * 请求结束时清掉。它只在请求线程上有效——**跨线程的异步会丢掉它**，
 * 而丢掉的表现是"没有租户"，这时 {@link TenantStore} 会把前缀写成 {@code default::}，
 * 也就是它自己的半个空间（不会静默落到别人的空间里）。
 */
public final class Tenant {

    /** 没识别出租户时的兜底。它必须是"某个"而不是"空"——空前缀等于不隔离。 */
    public static final String DEFAULT = "default";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Tenant() {
    }

    @FunctionalInterface
    public interface Resolver {
        String tenant();
    }

    public static String current() {
        String t = CURRENT.get();
        return t == null || t.isBlank() ? DEFAULT : t;
    }

    public static void set(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenant.trim());
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String scope(String tenant, String sessionId) {
        return (tenant == null || tenant.isBlank() ? DEFAULT : tenant.trim()) + "::" + sessionId;
    }
}
