package com.aplat.kernel;

import java.util.Map;
import java.util.Optional;

/**
 * 能力门面。业务代码只认 {@code ctx.get(Class)}，不 import 任何具体实现——
 * 这就是"可替换"的全部机制。
 *
 * <p>刻意做薄：没有 acquire/release 引用计数、没有作用域嵌套、没有 dispose 回调。
 */
public final class Ctx {

    private final SeamRegistry registry;
    private final EventBus bus;

    Ctx(SeamRegistry registry, EventBus bus) {
        this.registry = registry;
        this.bus = bus;
    }

    public <T> T get(Class<T> seam) {
        return registry.get(seam);
    }

    public <T> Optional<T> optional(Class<T> seam) {
        return registry.optional(seam);
    }

    public EventBus bus() {
        return bus;
    }

    public Map<String, String> describe() {
        return registry.describe();
    }
}
