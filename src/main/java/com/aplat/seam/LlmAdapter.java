package com.aplat.seam;

import java.util.function.Consumer;

/**
 * LLM 适配缝。任何 OpenAI 兼容服务都通过实现本接口接入；
 * 上层（agent-loop）只认流式回调，不知道是 HTTP 还是别的传输。
 */
public interface LlmAdapter extends Seam {

    /** 流式产出。实现方负责把上游 SSE 分片拼成完整 chunk 再回调。 */
    void stream(LlmRequest request, Consumer<LlmChunk> sink);

    /** 同步收口，返回累积文本与工具调用——便于测试与非流式场景。 */
    default LlmResult complete(LlmRequest request) {
        StringBuilder text = new StringBuilder();
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        String[] finish = {null};
        stream(request, chunk -> {
            switch (chunk) {
                case LlmChunk.TextDelta d -> text.append(d.text());
                case LlmChunk.ToolCallDelta d -> calls.add(d.call());
                case LlmChunk.Done d -> finish[0] = d.finishReason();
                case LlmChunk.Usage ignored -> {
                }
            }
        });
        return new LlmResult(text.toString(), java.util.List.copyOf(calls), finish[0]);
    }

    record LlmResult(String text, java.util.List<ToolCall> toolCalls, String finishReason) {
    }
}
