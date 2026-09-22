package com.aplat.loop;

import com.aplat.seam.SessionEvent;
import java.util.List;

/** 一次 turn 的结果。终止原因必须显式——"没答出来"和"答完了"不能混为一谈。 */
public record TurnResult(
        String sessionId,
        Status status,
        String finalText,
        int steps,
        List<SessionEvent> events) {

    public enum Status {
        /** 模型给出最终答复。 */
        COMPLETED,
        /** 步数用尽仍未收口。 */
        MAX_STEPS,
        /** 循环内部错误。 */
        ERROR
    }

    public TurnResult {
        events = List.copyOf(events);
    }

    public boolean ok() {
        return status == Status.COMPLETED;
    }
}
