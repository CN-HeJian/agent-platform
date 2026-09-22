package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U02 · SSE 帧格式的纯函数级验证。
 *
 * <p>不启服务、不碰网络——帧格式是这份协议里最容易"看起来对、接前端才发现错"的部分，
 * 所以它必须能被单独钉住。
 */
class SseWriterTest {

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    private SseWriter writer() {
        return new SseWriter(sink);
    }

    private String out() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static SessionEvent event(long seq, String type, Map<String, Object> payload) {
        return new SessionEvent("s1", seq, type, payload, Instant.EPOCH);
    }

    @Test
    @DisplayName("一条事件 = id/event/data 三个字段 + 空行分隔；事件名用 AG-UI 名而不是会话事件名")
    void writesWellFormedFrame() {
        SseWriter w = writer();

        w.event(event(7, "llm.chunk", Map.of("step", 1, "text", "你好")));

        String frame = out();
        assertTrue(frame.startsWith("id: 7\n"), "缺 id，浏览器就无法回传 Last-Event-ID");
        assertTrue(frame.contains("event: TEXT_MESSAGE_CONTENT\n"), "事件名必须是 AG-UI 的名字");
        assertTrue(frame.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""), "data 里也要带 type，供 onmessage 兜底");
        assertTrue(frame.endsWith("\n\n"), "帧必须以空行结束，否则前端不会派发");
    }

    @Test
    @DisplayName("data 永远单行——SSE 里换行会截断这条事件")
    void dataNeverContainsRawNewline() {
        SseWriter w = writer();

        w.event(event(1, "llm.chunk", Map.of("text", "第一行\n第二行\r\n第三行")));

        String frame = out();
        long dataLines = frame.lines().filter(l -> l.startsWith("data: ")).count();
        assertEquals(1, dataLines, "data 被拆成多行就是 bug");
        assertTrue(frame.contains("\\n"), "换行应被转义而不是原样写出");
    }

    @Test
    @DisplayName("没有 seq 的帧不写 id（回执类事件不该参与续传游标）")
    void frameWithoutSeqOmitsId() {
        SseWriter w = writer();

        w.event("RUN_RESULT", "{\"status\":\"COMPLETED\"}");

        assertFalse(out().contains("id: "));
        assertTrue(out().contains("event: RUN_RESULT"));
    }

    @Test
    @DisplayName("心跳是注释行，客户端会忽略、代理会当成活跃流量")
    void heartbeatIsComment() {
        SseWriter w = writer();

        w.heartbeat();

        assertEquals(": ping\n\n", out());
    }

    @Test
    @DisplayName("关闭后再写直接失败，不抛异常——业务线程不该因为对端断开而炸")
    void writingAfterCloseFailsQuietly() {
        SseWriter w = writer();
        w.event(event(1, "llm.chunk", Map.of("text", "a")));

        w.close("client gone");
        boolean accepted = w.event(event(2, "llm.chunk", Map.of("text", "b")));

        assertFalse(accepted);
        assertTrue(w.isClosed());
        assertEquals("client gone", w.closeReason());
        assertFalse(out().contains("\"text\":\"b\""), "关闭后不应再有字节写出");
    }

    @Test
    @DisplayName("写失败即自动关闭并唤醒等待者——这是断连检测的唯一途径")
    void writeFailureClosesAndReleasesWaiter() throws Exception {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("connection reset by peer");
            }
        };
        SseWriter w = new SseWriter(broken);
        CountDownLatch waiterWoke = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                w.awaitClosed(5_000);
                waiterWoke.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        w.event(event(1, "llm.chunk", Map.of("text", "x")));

        assertTrue(waiterWoke.await(3, TimeUnit.SECONDS), "等待者必须被唤醒，否则 SSE 处理线程会永久泄漏");
        assertTrue(w.isClosed());
        assertTrue(w.closeReason().startsWith("write failed"));
    }

    @Test
    @DisplayName("重复 close 幂等；frameCount 如实反映写出量")
    void closeIsIdempotent() {
        SseWriter w = writer();
        w.event(event(1, "turn.started", Map.of()));

        w.close("first");
        w.close("second");

        assertEquals("first", w.closeReason());
        assertEquals(1, w.frameCount());
    }
}
