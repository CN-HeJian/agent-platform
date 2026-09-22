package com.aplat.seam;

import java.time.Instant;
import java.util.Map;

/**
 * 会话事件——系统的唯一真相来源。
 *
 * <p>规则：**模型可见的，必已记录**。任何进上下文的输入/输出都必须先落成事件，
 * 否则回放复现就不可能。
 *
 * @param seq 会话内单调递增序号（从 1 开始）
 */
public record SessionEvent(
        String sessionId,
        long seq,
        String type,
        Map<String, Object> payload,
        Instant ts) {

    public SessionEvent {
        // 注意：这里刻意不用 Map.copyOf —— 它拒绝 null 值，而 errorCode 这类字段在成功路径上就是 null。
        payload = payload == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
        ts = ts == null ? Instant.now() : ts;
    }

    public Object get(String key) {
        return payload.get(key);
    }

    public String str(String key) {
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 人类可读的一行回放。 */
    public String line() {
        return "#" + seq + " " + type + " " + payload;
    }
}
