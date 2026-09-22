package com.aplat.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 事件总线：订阅可回收——这是"卸载即回收"在薄内核里的全部含义。 */
class EventBusTest {

    record Ping(String msg) {
    }

    @Test
    @DisplayName("已订阅者收到事件")
    void deliversToSubscriber() {
        EventBus bus = new EventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(Ping.class, p -> seen.add(p.msg()));
        bus.publish(new Ping("a"));
        assertEquals(List.of("a"), seen);
    }

    @Test
    @DisplayName("取消订阅后不再收到事件（回收干净）")
    void cancelledSubscriptionStopsReceiving() {
        EventBus bus = new EventBus();
        List<String> seen = new ArrayList<>();
        Subscription sub = bus.subscribe(Ping.class, p -> seen.add(p.msg()));
        bus.publish(new Ping("before"));
        sub.cancel();
        bus.publish(new Ping("after"));

        assertEquals(List.of("before"), seen);
        assertFalse(sub.active());
    }

    @Test
    @DisplayName("按父类型订阅也能收到子类型事件")
    void matchesSupertype() {
        EventBus bus = new EventBus();
        List<Object> seen = new ArrayList<>();
        bus.subscribe(Object.class, seen::add);
        bus.publish(new Ping("x"));
        assertEquals(1, seen.size());
    }

    @Test
    @DisplayName("publish(null) 不炸")
    void nullEventIgnored() {
        EventBus bus = new EventBus();
        bus.publish(null);
        assertTrue(true);
    }
}
