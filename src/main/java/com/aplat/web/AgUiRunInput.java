package com.aplat.web;

import com.aplat.seam.LlmMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AG-UI 的 {@code RunAgentInput} —— 客户端 POST 到 agent 端点的请求体。
 *
 * <p>只取我们真的用得上的部分，其余原样忽略（协议会持续演进，多余字段不该让服务端报错）：
 *
 * <pre>
 * {
 *   "threadId": "t-1",            // 会话身份 → 直接当我们的 sessionId
 *   "runId":    "r-1",            // 本次运行身份 → 回填到 RUN_STARTED / RUN_FINISHED
 *   "messages": [ {"role":"user","content":"..."} ],
 *   "state":    {}, "tools": [], "context": [], "forwardedProps": {}
 * }
 * </pre>
 *
 * <h2>两个刻意的取舍</h2>
 * <ol>
 *   <li><b>历史由客户端带来，服务端不重放</b>：AG-UI 的设计就是"客户端持有 thread 历史"，
 *       每次 run 把完整 messages 发过来。所以我们<b>不</b>从会话日志回填给前端（那会让
 *       UI 看到重复消息），而是把历史里的 user/assistant 文本<b>转成 LlmMessage 喂给循环</b>——
 *       否则多轮对话每轮都从零开始，模型完全不知道上一句说了什么。</li>
 *   <li><b>缺 threadId/runId 也能跑</b>：本地 curl 手测时不该被迫先造两个 id。</li>
 * </ol>
 *
 * <p>历史里只保留 user/assistant 的纯文本：{@code tool} 角色必须配对着
 * assistant 的 {@code tool_calls} 回显，OpenAI 协议才认；缺一半会让 provider 直接报错，
 * 所以宁可不带。
 *
 * @param userInput 最新一条 user 消息的文本；null = 请求里没有可执行的输入
 * @param history   最新一条 user 输入之前的 user/assistant 文本轮次
 */
public record AgUiRunInput(String threadId, String runId, String userInput, List<LlmMessage> history) {

    public AgUiRunInput {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** content 既可能是字符串，也可能是 [{type:"text", text:"..."}] 分片数组。 */
    public static AgUiRunInput parse(JsonNode root) {
        String threadId = textOrNull(root, "threadId");
        String runId = textOrNull(root, "runId");

        List<LlmMessage> turns = new ArrayList<>();
        int lastUserIndex = -1;

        JsonNode messages = root == null ? null : root.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                String role = message.path("role").asText("");
                String content = extractContent(message.get("content"));
                if (content == null || content.isBlank()) {
                    continue;
                }
                switch (role) {
                    case "user" -> {
                        turns.add(LlmMessage.user(content));
                        lastUserIndex = turns.size() - 1;
                    }
                    case "assistant" -> turns.add(LlmMessage.assistant(content));
                    default -> {
                        // tool / system / developer：见类注释，不带
                    }
                }
            }
        }

        if (lastUserIndex < 0) {
            return new AgUiRunInput(
                    orDefault(threadId, "thread-"), orDefault(runId, "run-"), null, List.of());
        }

        String input = turns.get(lastUserIndex).content();
        List<LlmMessage> history = List.copyOf(turns.subList(0, lastUserIndex));
        return new AgUiRunInput(orDefault(threadId, "thread-"), orDefault(runId, "run-"), input, history);
    }

    private static String extractContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else if (part.has("text")) {
                    sb.append(part.path("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String orDefault(String value, String prefix) {
        return value == null || value.isBlank()
                ? prefix + UUID.randomUUID().toString().substring(0, 8)
                : value;
    }
}
