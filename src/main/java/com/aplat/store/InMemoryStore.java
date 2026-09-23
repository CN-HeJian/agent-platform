package com.aplat.store;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 Store：开发与单测的零依赖实现。
 *
 * <p>它承担一个额外职责——**作为 MySQL 实现的契约参照**：两者必须跑通同一套契约测试。
 */
public final class InMemoryStore implements Store {

    /** 每条会话的 seq 计数器（不放进事件，避免为内存实现引入"读最大值"的伪需求）。 */
    private final Map<String, AtomicLong> seqs = new LinkedHashMap<>();
    private final Map<String, List<SessionEvent>> events = new LinkedHashMap<>();
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, String> idempotency = new LinkedHashMap<>();
    /** 命名空间 → (键 → JSON)。 */
    private final Map<String, Map<String, String>> kv = new LinkedHashMap<>();

    @Override
    public String id() {
        return "store.in-memory";
    }

    @Override
    public synchronized long append(SessionEvent event) {
        long seq = seqs.computeIfAbsent(event.sessionId(), k -> new AtomicLong(0)).incrementAndGet();
        events.computeIfAbsent(event.sessionId(), k -> new ArrayList<>())
                .add(new SessionEvent(event.sessionId(), seq, event.type(), event.payload(), event.ts()));
        return seq;
    }

    @Override
    public synchronized List<String> sessionIds() {
        // 排序与 JDBC 实现保持一致（那边是 ORDER BY）。契约说排序就必须两个都排——
        // 否则"同一个断言在内存上过、在 MySQL 上挂"，而这正是契约测试要消灭的东西。
        java.util.List<String> out = new java.util.ArrayList<>(events.keySet());
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    public synchronized List<SessionEvent> events(String sessionId, long afterSeq) {
        List<SessionEvent> all = events.getOrDefault(sessionId, List.of());
        List<SessionEvent> out = new ArrayList<>();
        for (SessionEvent e : all) {
            if (e.seq() > afterSeq) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public synchronized void saveSnapshot(Snapshot snapshot) {
        snapshots.put(snapshot.sessionId(), snapshot);
    }

    @Override
    public synchronized Optional<Snapshot> latestSnapshot(String sessionId) {
        return Optional.ofNullable(snapshots.get(sessionId));
    }

    @Override
    public synchronized boolean markIfAbsent(String idempotencyKey, String ref) {
        return idempotency.putIfAbsent(idempotencyKey, ref == null ? "" : ref) == null;
    }

    @Override
    public synchronized Optional<String> idempotentRef(String idempotencyKey) {
        return Optional.ofNullable(idempotency.get(idempotencyKey));
    }

    @Override
    public synchronized void putIdempotentRef(String idempotencyKey, String ref) {
        // 只覆盖已有键：不存在时**不创建**。否则一次拼错键名的写入会悄无声息地
        // 造出一条"幂等键"，而那正是幂等判断的依据——宁可什么都不做。
        if (idempotency.containsKey(idempotencyKey)) {
            idempotency.put(idempotencyKey, ref == null ? "" : ref);
        }
    }

    // ------------------------------------------------------------ 命名空间键值

    @Override
    public synchronized void put(String namespace, String key, String json) {
        kv.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).put(key, json);
    }

    @Override
    public synchronized Optional<String> get(String namespace, String key) {
        return Optional.ofNullable(kv.getOrDefault(namespace, Map.of()).get(key));
    }

    @Override
    public synchronized Map<String, String> all(String namespace) {
        return new LinkedHashMap<>(kv.getOrDefault(namespace, Map.of()));
    }

    @Override
    public synchronized void remove(String namespace, String key) {
        Map<String, String> ns = kv.get(namespace);
        if (ns != null) {
            ns.remove(key);
        }
    }

    /** 仅测试用：清空全部状态。 */
    public synchronized void clear() {
        seqs.clear();
        events.clear();
        snapshots.clear();
        idempotency.clear();
        kv.clear();
    }
}
