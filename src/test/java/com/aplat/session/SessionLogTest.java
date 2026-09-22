package com.aplat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import com.aplat.store.InMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 会话日志：append-only 语义 + AG-UI 投影。 */
class SessionLogTest {

    private EventSourcedSessionLog newLog() {
        return new EventSourcedSessionLog(new InMemoryStore());
    }

    @Test
    @DisplayName("seq 在会话内从 1 起单调递增，不同会话互不干扰")
    void seqIsMonotonicPerSession() {
        EventSourcedSessionLog log = newLog();
        assertEquals(1, log.append("s1", "a", Map.of()).seq());
        assertEquals(2, log.append("s1", "b", Map.of()).seq());
        assertEquals(3, log.append("s1", "c", Map.of()).seq());
        assertEquals(1, log.append("s2", "a", Map.of()).seq());
    }

    @Test
    @DisplayName("增量读取：用于 SSE 断线续传（Last-Event-ID）")
    void eventsAfterSupportsResume() {
        EventSourcedSessionLog log = newLog();
        for (int i = 0; i < 5; i++) {
            log.append("s1", "e" + i, Map.of("i", i));
        }
        List<SessionEvent> tail = log.eventsAfter("s1", 3);
        assertEquals(2, tail.size());
        assertEquals(4, tail.get(0).seq());
    }

    @Test
    @DisplayName("订阅者拿到追加的事件；取消后不再收到")
    void subscribeAndCancel() {
        EventSourcedSessionLog log = newLog();
        List<String> seen = new ArrayList<>();
        var sub = log.subscribe("s1", e -> seen.add(e.type()));

        log.append("s1", "first", Map.of());
        assertEquals(List.of("first"), seen);

        sub.cancel();
        log.append("s1", "second", Map.of());
        assertEquals(List.of("first"), seen);
    }

    @Test
    @DisplayName("事件类型直方图：排查时一眼看出哪一步缺事件")
    void histogramCountsTypes() {
        EventSourcedSessionLog log = newLog();
        log.append("s1", SessionLog.EV_TOOL_CALL, Map.of());
        log.append("s1", SessionLog.EV_TOOL_CALL, Map.of());
        log.append("s1", SessionLog.EV_TURN_CLOSED, Map.of());

        Map<String, Long> h = log.histogram("s1");
        assertEquals(2L, h.get(SessionLog.EV_TOOL_CALL));
        assertEquals(1L, h.get(SessionLog.EV_TURN_CLOSED));
    }

    @Test
    @DisplayName("回放是纯文本，能直接贴进 issue")
    void replayIsHumanReadable() {
        EventSourcedSessionLog log = newLog();
        log.append("s1", SessionLog.EV_INPUT, Map.of("text", "hello"));
        String replay = log.replay("s1");
        assertTrue(replay.contains("#1"));
        assertTrue(replay.contains("input.claimed"));
        assertTrue(replay.contains("hello"));
    }

    @Test
    @DisplayName("AG-UI 投影：事件名与协议对齐，前端换实现不需要动后端")
    void agUiMappingFollowsProtocol() {
        assertEquals("RUN_STARTED", AgUiMapper.agUiType(SessionLog.EV_TURN_START));
        assertEquals("TEXT_MESSAGE_CONTENT", AgUiMapper.agUiType(SessionLog.EV_LLM_CHUNK));
        assertEquals("TOOL_CALL_START", AgUiMapper.agUiType(SessionLog.EV_TOOL_CALL));
        assertEquals("TOOL_CALL_END", AgUiMapper.agUiType(SessionLog.EV_TOOL_RESULT));
        assertEquals("STATE_SNAPSHOT", AgUiMapper.agUiType(SessionLog.EV_STATE_SNAPSHOT));
        assertEquals("RUN_FINISHED", AgUiMapper.agUiType(SessionLog.EV_TURN_CLOSED));

        EventSourcedSessionLog log = newLog();
        SessionEvent e = log.append("s1", SessionLog.EV_TURN_START, Map.of("input", "hi"));
        Map<String, Object> agui = AgUiMapper.toAgUi(e);
        assertEquals("RUN_STARTED", agui.get("type"));
        assertEquals("s1", agui.get("sessionId"));
        assertEquals(1L, agui.get("seq"));
    }

    @Test
    @DisplayName("未映射的事件不会消失，而是落到 CUSTOM_* 兜住")
    void unknownEventFallsBackToCustom() {
        assertTrue(AgUiMapper.agUiType("weird.event").startsWith("CUSTOM_"));
        assertFalse(AgUiMapper.agUiType("weird.event").isBlank());
    }
}
