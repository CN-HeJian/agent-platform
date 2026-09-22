package com.aplat.seam;

import java.util.List;

/** 一次 LLM 请求。刻意只留最小面：system + 消息 + 可用工具。 */
public record LlmRequest(
        String systemPrompt,
        List<LlmMessage> messages,
        List<ToolSpec> tools,
        Integer maxOutputTokens) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static LlmRequest of(String systemPrompt, List<LlmMessage> messages, List<ToolSpec> tools) {
        return new LlmRequest(systemPrompt, messages, tools, null);
    }
}
