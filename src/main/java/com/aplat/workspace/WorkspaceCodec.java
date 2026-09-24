package com.aplat.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** 工作区与评论的 JSON 编解码（U32）。 */
final class WorkspaceCodec {

    static final WorkspaceCodec INSTANCE = new WorkspaceCodec();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkspaceCodec() {
    }

    String toJson(Workspace w) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", w.id());
        n.put("name", w.name());
        n.put("createdAt", w.createdAt());
        n.set("sessionIds", strings(w.sessionIds()));
        n.set("members", strings(w.members()));
        return n.toString();
    }

    Optional<Workspace> fromJson(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return Optional.of(new Workspace(
                    n.path("id").asText(),
                    n.path("name").asText(),
                    readStrings(n.path("sessionIds")),
                    readStrings(n.path("members")),
                    n.path("createdAt").asLong()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    String commentToJson(Comment c) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", c.id());
        n.put("workspaceId", c.workspaceId());
        n.put("sessionId", c.sessionId());
        n.put("eventSeq", c.eventSeq());
        n.put("author", c.author());
        n.put("text", c.text());
        n.put("ts", c.ts());
        return n.toString();
    }

    Optional<Comment> commentFromJson(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return Optional.of(new Comment(
                    n.path("id").asText(),
                    n.path("workspaceId").asText(),
                    n.path("sessionId").asText(),
                    n.path("eventSeq").asLong(),
                    n.path("author").asText(),
                    n.path("text").asText(),
                    n.path("ts").asLong()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    java.util.Map<String, Object> commentView(Comment c) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", c.id());
        out.put("sessionId", c.sessionId());
        out.put("eventSeq", c.eventSeq());
        out.put("author", c.author());
        out.put("text", c.text());
        out.put("ts", c.ts());
        return out;
    }

    private static ArrayNode strings(Set<String> values) {
        ArrayNode a = MAPPER.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    private static Set<String> readStrings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }
}
