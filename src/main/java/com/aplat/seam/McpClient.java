package com.aplat.seam;

import java.util.List;

/**
 * MCP 接入缝（阶段二）。接入的远端工具直接注册进 {@link ToolRegistry}，
 * 卸载时按名字回收——"时间组合性"在薄内核里就体现为这一对 register/unregister。
 */
public interface McpClient extends Seam {

    /** 发现某端点提供的工具（stdio / SSE / HTTP 三种传输对上层同构）。 */
    List<Binding> discover(String endpoint);

    /** 健康检查：返回 false 时调用方应把该端点的工具降级而非报错。 */
    boolean healthy(String endpoint);

    record Binding(ToolSpec spec, ToolHandler handler) {
    }
}
