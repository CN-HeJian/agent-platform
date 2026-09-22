package com.aplat.kernel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内事件总线。只服务于观测/审计这类"旁挂"需求；
 * 业务链路一律走构造注入的直接调用，不靠事件解耦——这是薄内核的克制之处。
 */
public final class EventBus {

    private final Map<Class<?>, List<Entry>> listeners = new ConcurrentHashMap<>();

    public <T> Subscription subscribe(Class<T> type, Consumer<T> listener) {
        Entry entry = new Entry(listener);
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(entry);
        return Subscription.of(() -> {
            List<Entry> list = listeners.get(type);
            if (list != null) {
                list.remove(entry);
            }
        });
    }

    public void publish(Object event) {
        if (event == null) {
            return;
        }
        for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
            List<Entry> subs = listeners.get(type);
            if (subs == null) {
                continue;
            }
            for (Entry sub : subs) {
                @SuppressWarnings("unchecked")
                Consumer<Object> c = (Consumer<Object>) sub.consumer;
                c.accept(event);
            }
        }
    }

    private static final class Entry {
        final Consumer<?> consumer;

        Entry(Consumer<?> consumer) {
            this.consumer = consumer;
        }
    }
}
