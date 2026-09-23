package com.aplat.seam;

import java.util.List;
import java.util.Optional;

/**
 * 工具注册缝。提供者负责注册与卸载——注意 {@code unregister} 必须把 schema 一并回收，
 * 否则模型还能看到一个已经消失的工具（这是最常见的"卸载不干净"）。
 */
public interface ToolRegistry extends Seam {

    void register(Tool tool);

    void unregister(String name);

    Optional<Tool> find(String name);

    List<ToolSpec> specs();

    /**
     * 全部已注册工具（含"是否需批准"这类声明）。
     *
     * <p>与 {@link #specs()} 的区别：{@code specs()} 只给模型看（名字/说明/schema），
     * 而这个给<b>管理面与前端</b>看——前端要靠它知道"有哪些工具、哪个危险"，
     * 才不用把工具名硬编码在界面代码里。
     */
    List<Tool> all();

    default boolean contains(String name) {
        return find(name).isPresent();
    }
}
