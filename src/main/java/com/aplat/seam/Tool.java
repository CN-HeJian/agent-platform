package com.aplat.seam;

/**
 * 一个已注册的工具 = 声明 + 实现。
 *
 * @param approvalRequired 是否必须经 HITL 批准才能执行（危险工具置 true）
 */
public record Tool(ToolSpec spec, ToolHandler handler, boolean approvalRequired) {

    public static Tool of(ToolSpec spec, ToolHandler handler) {
        return new Tool(spec, handler, false);
    }

    public static Tool requiringApproval(ToolSpec spec, ToolHandler handler) {
        return new Tool(spec, handler, true);
    }

    public String name() {
        return spec.name();
    }
}
