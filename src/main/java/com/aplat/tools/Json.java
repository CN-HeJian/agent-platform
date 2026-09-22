package com.aplat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 工具参数解析小工具。解析失败不抛异常——管线要把"参数非法"变成结构化结果回给模型自纠。 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String argString(String argumentsJson, String field) {
        try {
            JsonNode node = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValidObject(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return true;
        }
        try {
            return MAPPER.readTree(argumentsJson).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
