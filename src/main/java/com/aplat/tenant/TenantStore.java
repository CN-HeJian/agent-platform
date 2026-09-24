package com.aplat.tenant;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import java.util.List;
import java.util.Optional;

/**
 * 按租户隔离的 Store（U33）：给命名空间与会话 id 都加上租户前缀。
 *
 * <h2>为什么隔离要做在存储这一层</h2>
 *
 * <p>另一处候选是"每个接口都检查一下 tenant"。那种做法的问题不是麻烦，
 * 而是**漏一处就是一次越权**：检查散落在几十个方法里，而漏掉的那个看起来完全正常。
 * 做在存储层之后，调用方想越权也拿不到——key 根本不在它那半个命名空间里。
 *
 * <h2>会话 id 也要加前缀</h2>
 *
 * <p>只隔离命名空间还不够：会话事件那张表是**全局**的，而 sessionId 是业务给的
 * （前端传什么就是什么）。于是 A 租户只要猜到 B 的 sessionId 就能读到它的事件——
 * 而 sessionId 经常是 {@code s-1} 这种可猜的东西。所以 sessionId 也要加前缀。
 *
 * <p>代价是**库里存的和外面看到的不一样**。这个代价是值得的，而且必须写清楚：
 * 直接查库做排查时，看到的会话 id 会带 {@code <tenant>::} 前缀。
 */
public final class TenantStore implements Store {

    private final Store delegate;
    private final Tenant.Resolver resolver;

    public TenantStore(Store delegate, Tenant.Resolver resolver) {
        this.delegate = delegate;
        this.resolver = resolver;
    }

    private String tenant() {
        return resolver.tenant();
    }

    private String ns(String namespace) {
        return tenant() + "::" + namespace;
    }

    private String session(String sessionId) {
        return tenant() + "::" + sessionId;
    }

    @Override
    public String id() {
        return delegate.id() + "[tenant]";
    }

    @Override
    public Store unwrap() {
        return delegate.unwrap();
    }

    @Override
    public long append(SessionEvent event) {
        // 换 sessionId 只能重建一条：record 是不可变的（这是好事——
        // 它保证了"事件一旦写下就没人改得动"，而这正是事件溯源的前提）
        return delegate.append(new SessionEvent(session(event.sessionId()), event.seq(),
                event.type(), event.payload(), event.ts()));
    }

    @Override
    public List<SessionEvent> events(String sessionId, long afterSeq) {
        return delegate.events(session(sessionId), afterSeq);
    }

    @Override
    public List<String> sessionIds() {
        // 剥掉前缀再交给上层：租户不该在自己的视图里看到一堆前缀
        String prefix = tenant() + "::";
        List<String> out = new java.util.ArrayList<>();
        for (String id : delegate.sessionIds()) {
            if (id.startsWith(prefix)) {
                out.add(id.substring(prefix.length()));
            }
        }
        return out;
    }

    @Override
    public void saveSnapshot(Snapshot snapshot) {
        delegate.saveSnapshot(new Snapshot(session(snapshot.sessionId()), snapshot.stepId(),
                snapshot.stateJson(), snapshot.ts()));
    }

    @Override
    public Optional<Snapshot> latestSnapshot(String sessionId) {
        return delegate.latestSnapshot(session(sessionId));
    }

    @Override
    public boolean markIfAbsent(String idempotencyKey, String ref) {
        return delegate.markIfAbsent(ns("idem") + "/" + idempotencyKey, ref);
    }

    @Override
    public Optional<String> idempotentRef(String idempotencyKey) {
        return delegate.idempotentRef(ns("idem") + "/" + idempotencyKey);
    }

    @Override
    public void putIdempotentRef(String idempotencyKey, String ref) {
        delegate.putIdempotentRef(ns("idem") + "/" + idempotencyKey, ref);
    }

    @Override
    public void put(String namespace, String key, String json) {
        delegate.put(ns(namespace), key, json);
    }

    @Override
    public Optional<String> get(String namespace, String key) {
        return delegate.get(ns(namespace), key);
    }

    @Override
    public java.util.Map<String, String> all(String namespace) {
        return delegate.all(ns(namespace));
    }

    @Override
    public void remove(String namespace, String key) {
        delegate.remove(ns(namespace), key);
    }
}
