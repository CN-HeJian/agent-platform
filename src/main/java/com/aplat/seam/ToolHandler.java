package com.aplat.seam;

/** 工具实现体。拿到调用参数，返回结构化结果；抛出异常会被管线兜成 SANDBOX_FAILURE。 */
@FunctionalInterface
public interface ToolHandler {
    ToolResult handle(ToolCall call) throws Exception;
}
