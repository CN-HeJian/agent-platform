package com.aplat.durable;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 给其它包用的一个 JSON 写出入口（ObjectMapper 是线程安全的，共用一份）。 */
public final class DurableCodecJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DurableCodecJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }
}
