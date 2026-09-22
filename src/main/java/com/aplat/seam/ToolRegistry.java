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

    default boolean contains(String name) {
        return find(name).isPresent();
    }
}
