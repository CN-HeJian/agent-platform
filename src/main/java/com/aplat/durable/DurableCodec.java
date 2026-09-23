package com.aplat.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aplat.seam.LlmMessage;
import com.aplat.seam.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 耐久层里那些"要落库的对象" ⇄ JSON 的编解码（U19/U20）。
 *
 * <p>刻意**不给 seam 里的 record 加 Jackson 注解**：{@code LlmMessage}、{@code ToolCall}
 * 是能力缝的公共类型，不该知道"自己会被序列化成什么形状"。序列化格式属于持久化层的实现细节，
 * 所以映射代码集中在这里——也正因为集中，改格式只有一个地方要看。
 *
 * <p>手写映射而不是用反射（{@code convertValue}）：字段少、但每个字段的**缺失语义**都要想清楚
 * （旧数据里没有的字段该退化成什么）。反射会把这种决策藏起来。
 */
final class DurableCodec {

    private DurableCodec() {
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ 任务

    static String taskToJson(TaskRecord t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("taskId", t.taskId());
        n.put("sessionId", t.sessionId());
        n.put("goal", t.goal());
        n.put("state", t.state().name());
        n.put("step", t.step());
        n.put("attempts", t.attempts());
        n.put("detail", t.detail() == null ? "" : t.detail());
        n.put("createdAt", t.createdAt());
        n.put("updatedAt", t.updatedAt());
        return n.toString();
    }

    static Optional<TaskRecord> taskFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            return Optional.of(new TaskRecord(
                    n.path("taskId").asText(),
                    n.path("sessionId").asText(),
                    n.path("goal").asText(),
                    // 状态名不认识时退化成 CRASHED 而不是抛：一条读不懂的旧记录，
                    // 最好的处置是"当成没跑完、允许续跑"，而不是让整个任务列表炸掉
                    parseState(n.path("state").asText()),
                    n.path("step").asInt(0),
                    n.path("attempts").asInt(0),
                    n.path("detail").asText(""),
                    n.path("createdAt").asLong(0),
                    n.path("updatedAt").asLong(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static TaskState parseState(String raw) {
        try {
            return TaskState.valueOf(raw);
        } catch (Exception e) {
            return TaskState.CRASHED;
        }
    }

    // -------------------------------------------------------------- 检查点

    static String messagesToJson(int step, List<LlmMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("step", step);
        var arr = root.putArray("messages");
        for (LlmMessage m : messages) {
            ObjectNode n = arr.addObject();
            n.put("role", m.role());
            n.put("content", m.content() == null ? "" : m.content());
            if (m.toolCallId() != null) {
                n.put("toolCallId", m.toolCallId());
            }
            if (!m.toolCalls().isEmpty()) {
                var calls = n.putArray("toolCalls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode cn = calls.addObject();
                    cn.put("id", c.id());
                    cn.put("name", c.name());
                    cn.put("argumentsJson", c.argumentsJson());
                }
            }
        }
        return root.toString();
    }

    static Optional<Checkpointer.Checkpoint> checkpointFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            List<LlmMessage> messages = new ArrayList<>();
            for (JsonNode n : root.path("messages")) {
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode c : n.path("toolCalls")) {
                    calls.add(new ToolCall(c.path("id").asText(), c.path("name").asText(),
                            c.path("argumentsJson").asText()));
                }
                messages.add(new LlmMessage(
                        n.path("role").asText(),
                        n.path("content").asText(""),
                        n.hasNonNull("toolCallId") ? n.path("toolCallId").asText() : null,
                        calls));
            }
            return Optional.of(new Checkpointer.Checkpoint(root.path("step").asInt(0), messages));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ 工具结果

    static String toolResultToJson(boolean ok, String content, String errorCode) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("ok", ok);
        n.put("content", content == null ? "" : content);
        if (errorCode != null) {
            n.put("errorCode", errorCode);
        }
        return n.toString();
    }

    static Optional<String[]> toolResultFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            return Optional.of(new String[]{
                    n.path("ok").asText("false"),
                    n.path("content").asText(""),
                    n.hasNonNull("errorCode") ? n.path("errorCode").asText() : null});
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
