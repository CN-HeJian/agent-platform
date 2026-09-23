package com.aplat.web;

import java.util.Optional;

/**
 * 一次请求的现场（U17）：为了让"审计"能拿到**最终状态码**。
 *
 * <p>为什么需要它：响应是各个 handler 自己写出去的（`sendJson` / `sendError` / SSE），
 * 路由层在 handler 返回时并不知道"刚才到底回了 200 还是 404"。JDK 的 HttpExchange
 * 也不暴露"已发送的状态码"。所以用一个 request-scoped 的持有者把状态记下来。
 *
 * <p>用 {@link ThreadLocal} 是安全的：每个请求跑在自己的虚拟线程上，
 * 而且路由的 finally 里必定 {@link #end()} 清掉，不会串到别的请求、
 * 也不会在虚拟线程复用时残留。
 */
final class RequestScope {

    private static final ThreadLocal<RequestScope> CURRENT = new ThreadLocal<>();

    /** 404 / 405 这类由路由层直接判定的状态，在 handler 之外也要能记上。 */
    static final String NOTE_PUBLIC = "public";

    final String identity;
    final String clientIp;
    final String method;
    final String path;

    private volatile int status;
    private volatile String note;

    private RequestScope(String identity, String clientIp, String method, String path) {
        this.identity = identity;
        this.clientIp = clientIp;
        this.method = method;
        this.path = path;
    }

    static RequestScope begin(String identity, String clientIp, String method, String path) {
        RequestScope scope = new RequestScope(identity, clientIp, method, path);
        CURRENT.set(scope);
        return scope;
    }

    static Optional<RequestScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void end() {
        CURRENT.remove();
    }

    /** 由发送响应的那几处调用，记下最终状态码。 */
    void responded(int status) {
        this.status = status;
    }

    void note(String note) {
        this.note = note;
    }

    int status() {
        return status;
    }

    String note() {
        return note;
    }
}
