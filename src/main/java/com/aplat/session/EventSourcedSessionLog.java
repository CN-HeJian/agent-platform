package com.aplat.session;

import com.aplat.kernel.Subscription;
import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 基于 {@link Store} 的事件溯源会话日志。
 *
 * <p>内存/MySQL 的差别全被 Store 吸收，这里只管"追加 + 广播 + 投影"。
 */
public final class EventSourcedSessionLog implements SessionLog {

    private final Store store;
    private final Map<String, List<Consumer<SessionEvent>>> subscribers = new ConcurrentHashMap<>();

    public EventSourcedSessionLog(Store store) {
        this.store = store;
    }

    @Override
    public String id() {
        return "session-log.event-sourced";
    }

    @Override
    public SessionEvent append(String sessionId, String type, Map<String, Object> payload) {
        SessionEvent event = new SessionEvent(sessionId, 0L, type, payload, java.time.Instant.now());
        long seq = store.append(event);
        SessionEvent stored = new SessionEvent(sessionId, seq, type, payload, event.ts());
        for (Consumer<SessionEvent> sub : subscribers.getOrDefault(sessionId, List.of())) {
            sub.accept(stored);
        }
        return stored;
    }

    @Override
    public List<SessionEvent> events(String sessionId) {
        return store.events(sessionId, 0L);
    }

    @Override
    public List<SessionEvent> eventsAfter(String sessionId, long seq) {
        return store.events(sessionId, seq);
    }

    @Override
    public Subscription subscribe(String sessionId, Consumer<SessionEvent> listener) {
        List<Consumer<SessionEvent>> list =
                subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return Subscription.of(() -> list.remove(listener));
    }

    /** 事件类型直方图——排查时先看哪个阶段的事件异常增多/缺失。 */
    public Map<String, Long> histogram(String sessionId) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (SessionEvent e : events(sessionId)) {
            out.merge(e.type(), 1L, Long::sum);
        }
        return out;
    }

    /** 只取某一类事件，供断言与投影使用。 */
    public List<SessionEvent> ofType(String sessionId, String type) {
        List<SessionEvent> out = new ArrayList<>();
        for (SessionEvent e : events(sessionId)) {
            if (e.type().equals(type)) {
                out.add(e);
            }
        }
        return out;
    }
}
