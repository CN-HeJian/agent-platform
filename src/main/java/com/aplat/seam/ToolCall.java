package com.aplat.seam;

/** 模型发起的一次工具调用。 */
public record ToolCall(String id, String name, String argumentsJson) {

    public static ToolCall of(String name, String argumentsJson) {
        return new ToolCall("call_" + Integer.toHexString((name + argumentsJson).hashCode()), name, argumentsJson);
    }
}
