package com.aplat.seam;

/**
 * 能力缝的根标记。所有可替换组件都实现它，装配清单靠它枚举。
 *
 * <p>约定：能力缝是**接口**，实现是**普通类**——没有插件壳、没有 lifecycle 回调。
 */
public interface Seam {

    /** 稳定标识，用于日志、配置与排查。 */
    String id();
}
