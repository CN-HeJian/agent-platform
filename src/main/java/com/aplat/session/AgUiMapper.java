package com.aplat.session;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话事件 → AG-UI 事件的投影。
 *
 * <p>这是"前端可替换"的唯一保险：只要我们吐的 AG-UI 事件名与语义严格对齐，
 * 前端换 CopilotKit 还是自研都不需要动后端。
 */
public final class AgUiMapper {

    private AgUiMapper() {
    }

    private static final Map<String, String> TABLE = Map.ofEntries(
            Map.entry(SessionLog.EV_TURN_START, "RUN_STARTED"),
            Map.entry(SessionLog.EV_STEP_START, "STEP_STARTED"),
            Map.entry(SessionLog.EV_LLM_CHUNK, "TEXT_MESSAGE_CONTENT"),
            Map.entry(SessionLog.EV_TOOL_CALL, "TOOL_CALL_START"),
            Map.entry(SessionLog.EV_TOOL_RESULT, "TOOL_CALL_END"),
            Map.entry(SessionLog.EV_STATE_SNAPSHOT, "STATE_SNAPSHOT"),
            Map.entry(SessionLog.EV_TURN_CLOSED, "RUN_FINISHED"),
            Map.entry(SessionLog.EV_ERROR, "RUN_ERROR"));

    public static String agUiType(String sessionEventType) {
        return TABLE.getOrDefault(sessionEventType, "CUSTOM_" + sessionEventType.toUpperCase().replace('.', '_'));
    }

    /** 投影成可直接喂给前端的事件对象。 */
    public static Map<String, Object> toAgUi(SessionEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", agUiType(event.type()));
        out.put("sessionId", event.sessionId());
        out.put("seq", event.seq());
        out.put("payload", event.payload());
        return out;
    }
}
