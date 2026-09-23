package com.aplat.tools;

import com.aplat.seam.Tool;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认工具注册表。
 *
 * <p>{@link #unregister} 必须同时回收 schema（本实现的注册表与 schema 是同一份数据，
 * 所以自然一致）——这是"MCP 端点掉线后模型不该再看到它的工具"的保证。
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    @Override
    public String id() {
        return "tools.registry.default";
    }

    @Override
    public synchronized void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    @Override
    public synchronized void unregister(String name) {
        tools.remove(name);
    }

    @Override
    public synchronized Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public synchronized List<ToolSpec> specs() {
        List<ToolSpec> out = new ArrayList<>();
        tools.values().forEach(t -> out.add(t.spec()));
        return out;
    }

    @Override
    public synchronized List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public synchronized int size() {
        return tools.size();
    }
}
