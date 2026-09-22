package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U02 / U09 · "回填 + 实时"缝合的正确性。
 *
 * <p>续传最容易出的两个 bug——<b>漏事件</b>和<b>重事件</b>——在真实网络下都很难复现
 * （要靠运气撞上回填与实时的时间重叠）。所以这里用确定性的顺序把那段竞态摆出来验。
 */
class EventPumpTest {

    private static final Pattern ID = Pattern.compile("^id: (\\d+)$", Pattern.MULTILINE);

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    private List<Long> ids() {
        Matcher m = ID.matcher(sink.toString(StandardCharsets.UTF_8));
        List<Long> out = new ArrayList<>();
        while (m.find()) {
            out.add(Long.parseLong(m.group(1)));
        }
        return out;
    }

    private static SessionEvent ev(long seq) {
        return new SessionEvent("s1", seq, "llm.chunk", Map.of("step", 1), Instant.EPOCH);
    }

    @Test
    @DisplayName("正常路径：回填历史 + 实时直写，顺序与去重都对")
    void backfillThenLive() {
        EventPump pump = new EventPump(new SseWriter(sink));

        pump.beginBackfill(3);
        pump.backfill(List.of(ev(4), ev(5)));
        pump.goLive();
        pump.accept(ev(6));

        assertEquals(List.of(4L, 5L, 6L), ids());
        assertEquals(6L, pump.watermark());
        assertEquals(3L, pump.written());
    }

    @Test
    @DisplayName("实时里重复推来已回填过的事件（重叠区）→ 不重推")
    void overlappingEventsAreNotDuplicated() {
        EventPump pump = new EventPump(new SseWriter(sink));

        pump.beginBackfill(3);
        pump.backfill(List.of(ev(4), ev(5)));
        pump.goLive();
        pump.accept(ev(5)); // 与回填重叠
        pump.accept(ev(4)); // 更早的迟到事件

        assertEquals(List.of(4L, 5L), ids(), "已写过的 seq 必须被 watermark 挡掉");
    }

    @Test
    @DisplayName("回填期间到达的事件先进缓冲，goLive 时补写 → 一条不漏")
    void eventsArrivingDuringBackfillAreNotLost() {
        EventPump pump = new EventPump(new SseWriter(sink));

        pump.beginBackfill(0);
        pump.accept(ev(1)); // 回填还没开始读库，事件先到
        pump.accept(ev(2));
        assertEquals(2, pump.buffered(), "回填期间必须缓冲而不是写出");

        pump.backfill(List.of(ev(1), ev(2))); // 读库时它们也在历史里
        pump.goLive();

        assertEquals(List.of(1L, 2L), ids(), "缓冲与历史重叠，只应写一次");
        assertEquals(0, pump.buffered());
    }

    @Test
    @DisplayName("最容易漏的窗口：订阅已生效、回填还没读到它 —— goLive 必须从缓冲补上")
    void eventInSubscribeToBackfillWindowIsRecovered() {
        EventPump pump = new EventPump(new SseWriter(sink));

        pump.accept(ev(1));                 // subscribe 之后、beginBackfill 之前
        pump.beginBackfill(0);
        pump.backfill(List.of());           // 库里这次没读到它（真实竞态）
        pump.goLive();

        assertEquals(List.of(1L), ids(), "这条漏了，前端就会缺一条事件且再也补不回来");
    }

    @Test
    @DisplayName("lastEventId 语义：只补客户端没见过的部分")
    void watermarkHonoursLastEventId() {
        EventPump pump = new EventPump(new SseWriter(sink));

        pump.beginBackfill(5); // 客户端说"我收到 5 了"
        pump.backfill(List.of(ev(4), ev(5), ev(6)));
        pump.goLive();

        assertEquals(List.of(6L), ids());
        assertTrue(pump.watermark() == 6L);
    }
}
