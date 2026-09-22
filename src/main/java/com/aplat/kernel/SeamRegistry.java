package com.aplat.kernel;

import com.aplat.seam.Seam;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 薄内核的服务台（能力缝注册表）。
 *
 * <p>设计基线里"组件"的边界就是一张 {@link com.aplat.seam.Seam} 表：接口即能力缝，
 * 实现即提供者。这里只做三件事——绑定、取用、单点替换，没有插件装卸、没有拓扑排序。
 *
 * <p>线程安全：绑定只发生在启动装配阶段；替换（{@link #replace}）用于测试与热换实现，
 * 用 synchronized 保护。
 */
public final class SeamRegistry {

    private final Map<Class<?>, Object> bindings = new LinkedHashMap<>();

    /** 绑定一条能力缝。重复绑定同一接口会直接失败，避免装配期蒙混过关。 */
    public synchronized <T> void bind(Class<T> seam, T impl) {
        if (seam == null || impl == null) {
            throw new IllegalArgumentException("seam/impl must not be null");
        }
        if (!seam.isInterface()) {
            throw new IllegalArgumentException("seam must be an interface: " + seam.getName());
        }
        if (!seam.isInstance(impl)) {
            throw new IllegalArgumentException(
                    impl.getClass().getName() + " does not implement " + seam.getName());
        }
        Object old = bindings.putIfAbsent(seam, impl);
        if (old != null) {
            throw new IllegalStateException("seam already bound: " + seam.getName()
                    + " (use replace() to swap it)");
        }
    }

    /** 取用一条能力缝；缺失即失败——消费方不需要写 null 判断。 */
    public synchronized <T> T get(Class<T> seam) {
        Object impl = bindings.get(seam);
        if (impl == null) {
            throw new MissingSeamException(seam);
        }
        return seam.cast(impl);
    }

    /** 可选取用：用于"有则增强、无则降级"的插件式行为。 */
    public synchronized <T> Optional<T> optional(Class<T> seam) {
        return Optional.ofNullable(bindings.get(seam)).map(seam::cast);
    }

    /**
     * 单点替换实现。这是"薄内核"相对完整插件化保留的核心收益：
     * 换提供者时消费方零改动。
     */
    public synchronized <T> T replace(Class<T> seam, T impl) {
        if (!seam.isInstance(impl)) {
            throw new IllegalArgumentException(
                    impl.getClass().getName() + " does not implement " + seam.getName());
        }
        return seam.cast(bindings.put(seam, impl));
    }

    public synchronized boolean isBound(Class<?> seam) {
        return bindings.containsKey(seam);
    }

    public synchronized Set<Class<?>> boundSeams() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    /** 人类可读的装配清单，供启动日志与排查使用。 */
    public synchronized Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        bindings.forEach((seam, impl) -> out.put(seam.getSimpleName(), nameOf(impl)));
        return Collections.unmodifiableMap(out);
    }

    /** 匿名实现没有简单类名，退化成能力缝自己的 id —— 清单必须永远可读。 */
    private static String nameOf(Object impl) {
        String simple = impl.getClass().getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        return impl instanceof Seam s ? s.id() : impl.getClass().getName();
    }
}
