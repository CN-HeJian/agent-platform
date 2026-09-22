package com.aplat.seam;

import com.aplat.kernel.Subscription;
import java.util.List;
import java.util.function.Consumer;

/**
 * 会话日志缝（append-only）。
 *
 * <p>这是排查之根：一次出问题的运行，靠 {@code events()} 回放就能复现当时模型看到的全部输入。
 * 也是对外流式推送的唯一数据源——AG-UI 事件由它投影而来，而不是各处零散地 push。
 */
public interface SessionLog extends Seam {

    /** 追加一条事件，返回落库后的事件（含分配好的 seq）。 */
    SessionEvent append(String sessionId, String type, java.util.Map<String, Object> payload);

    /** 全量事件。 */
    List<SessionEvent> events(String sessionId);

    /** 增量事件，用于 SSE 断线续传（Last-Event-ID）。 */
    List<SessionEvent> eventsAfter(String sessionId, long seq);

    /** 订阅新事件（在线推送用），返回可回收句柄。 */
    Subscription subscribe(String sessionId, Consumer<SessionEvent> listener);

    /** 人类可读回放，排查时直接打出来看。 */
    default String replay(String sessionId) {
        StringBuilder sb = new StringBuilder();
        for (SessionEvent e : events(sessionId)) {
            sb.append(e.line()).append('\n');
        }
        return sb.toString();
    }

    // ---- 事件类型常量：命名即契约，别在调用处写裸字符串 ----
    String EV_INPUT = "input.claimed";
    String EV_TURN_START = "turn.started";
    String EV_TURN_CLOSED = "turn.closed";
    String EV_STEP_START = "step.started";
    String EV_LLM_CHUNK = "llm.chunk";
    String EV_TOOL_CALL = "tool.call";
    String EV_TOOL_RESULT = "tool.result";
    String EV_HITL_REQUEST = "hitl.requested";
    String EV_HITL_RESOLVED = "hitl.resolved";
    String EV_CONTEXT_PREPARED = "context.prepared";
    String EV_STATE_SNAPSHOT = "state.snapshot";
    String EV_ERROR = "error";
}
