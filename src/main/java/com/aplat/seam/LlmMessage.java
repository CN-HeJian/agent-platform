package com.aplat.seam;

import java.util.List;

/**
 * 一条发给模型的消息。role ∈ system / user / assistant / tool。
 * assistant 若发起过工具调用，用 {@code toolCalls} 带回来（OpenAI 协议要求回显）。
 */
public record LlmMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

    public LlmMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, List.of());
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, List.of());
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, null, List.of());
    }

    public static LlmMessage assistantToolCalls(String content, List<ToolCall> calls) {
        return new LlmMessage("assistant", content == null ? "" : content, null, calls);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, toolCallId, List.of());
    }
}
