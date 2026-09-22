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

    /** 仅测试用：清空全部状态。 */
    public synchronized void clear() {
        seqs.clear();
        events.clear();
        snapshots.clear();
        idempotency.clear();
    }
}
