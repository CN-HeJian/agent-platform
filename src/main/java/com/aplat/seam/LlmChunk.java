package com.aplat.seam;

/**
 * 模型流式输出的最小单元。用回调式流（非响应式）以保持零框架依赖；
 * 换成 Reactor/Flux 只是适配层的事。
 */
public sealed interface LlmChunk {

    /** 文本增量。 */
    record TextDelta(String text) implements LlmChunk {
    }

    /** 一次完整的工具调用（流式分片已在上游拼装完毕）。 */
    record ToolCallDelta(ToolCall call) implements LlmChunk {
    }

    /** 用量统计，用于预算与成本观测。 */
    record Usage(int promptTokens, int completionTokens) implements LlmChunk {
    }

    /** 流结束。finishReason ∈ stop / tool_calls / length / error。 */
    record Done(String finishReason) implements LlmChunk {
    }
}
