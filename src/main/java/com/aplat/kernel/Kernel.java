package com.aplat.kernel;

import com.aplat.seam.Capabilities;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;

/**
 * 启动装配：一次性把实现绑定到能力缝，之后不再变化。
 *
 * <p>没有 PluginHost、没有依赖图、没有运行时装卸。换成 Spring 时，
 * 这个类退化为一个 {@code @Configuration}，{@link Ctx} 退化为容器门面。
 */
public final class Kernel {

    private final SeamRegistry registry = new SeamRegistry();
    private final EventBus bus = new EventBus();
    private final Ctx ctx = new Ctx(registry, bus);

    private Kernel() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public Ctx ctx() {
        return ctx;
    }

    public SeamRegistry registry() {
        return registry;
    }

    public EventBus bus() {
        return bus;
    }

    public static final class Builder {

        private final Kernel kernel = new Kernel();

        public <T> Builder bind(Class<T> seam, T impl) {
            kernel.registry.bind(seam, impl);
            return this;
        }

        /** 默认装配：内存 Store + 基于 Store 的事件日志，其余能力缝留给调用方。 */
        public Builder withDefaults() {
            if (!kernel.registry.isBound(Store.class)) {
                kernel.registry.bind(Store.class, new InMemoryStore());
            }
            if (!kernel.registry.isBound(SessionLog.class)) {
                kernel.registry.bind(SessionLog.class,
                        new EventSourcedSessionLog(kernel.registry.get(Store.class)));
            }
            return this;
        }

        /**
         * 已绑定的会话日志（{@code withDefaults()} 之后可用）。
         *
         * <p>给装配根解决一个先后问题：有些实现（如 {@link com.aplat.hitl.InteractiveHitl}）
         * 需要往会话日志里写事件，而日志是内核建的——它没法先于内核被 new 出来。
         * 在这里把日志取出来交给工厂方法，比在别处搞"延迟注入"要老实。
         */
        public SessionLog sessionLog() {
            return kernel.registry.optional(SessionLog.class)
                    .orElseThrow(() -> new MissingSeamException(SessionLog.class));
        }

        public Kernel build() {
            // 会话日志是排查之根，装配期就必须在
            if (!kernel.registry.isBound(SessionLog.class)) {
                throw new MissingSeamException(SessionLog.class);
            }
            return kernel;
        }
    }

    /** 能力缝自检：启动时打印一行装配清单，排查时先看这行。 */
    public String assemblyReport() {
        StringBuilder sb = new StringBuilder("[kernel] seams:");
        registry.describe().forEach((seam, impl) -> sb.append("\n  - ").append(seam).append(" -> ").append(impl));
        sb.append("\n[kernel] replaceable seams: ").append(Capabilities.REPLACEABLE.size());
        return sb.toString();
    }
}
