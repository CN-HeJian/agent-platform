package com.aplat.seam;

import java.util.List;

/**
 * 能力缝清单。这份表是"薄内核 + 少量可替换组件"的落地口径：
 * 只有会换实现、或需要独立验证的点才进这里，其余都是普通模块。
 */
public final class Capabilities {

    private Capabilities() {
    }

    /** 可替换组件（阶段一 7 个 + 阶段二 2 个）。每一个都要求：契约测试 + 单点替换。 */
    public static final List<Class<? extends Seam>> REPLACEABLE = List.of(
            LlmAdapter.class,
            ToolRegistry.class,
            Sandbox.class,
            ContextProvider.class,
            Hitl.class,
            SessionLog.class,
            Store.class,
            McpClient.class,
            Durable.class);

    /** 普通模块（不进服务台，直接 new 或用时注入）。 */
    public static final List<String> MODULES = List.of(
            "config", "web/transport", "agent-loop", "auth", "audit",
            "observability", "policy", "scheduler", "idempotency", "ui");
}
