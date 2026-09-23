package com.aplat.web;

import com.aplat.seam.SessionEvent;
import com.aplat.session.AgUiMapper;
import com.aplat.tools.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSE 帧写入器——传输层的唯一出口。
 *
 * <p>三件事必须在正确的层做，所以它很小：
 * <ul>
 *   <li><b>帧格式</b>：{@code id} / {@code event} / {@code data} 三个字段，事件名直接用 AG-UI 类型，
 *       这样浏览器 {@code EventSource} 里 {@code evt.type} 就是前端认的那个名字；</li>
 *   <li><b>写序</b>：用 {@link ReentrantLock} 而不是 {@code synchronized}——虚拟线程下
 *       {@code synchronized} 里有阻塞 IO 会 pin 住载体线程；</li>
 *   <li><b>断连即停</b>：任何一次写失败就标记关闭并释放等待者，绝不把异常抛回业务线程。</li>
 * </ul>
 *
 * <p>不订阅、不缓冲、不管生命周期——那是 {@link EventPump} 和 {@link HttpTransport} 的事。
 */
public final class SseWriter {

    private final OutputStream out;
    private final boolean namedEvents;
    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final AtomicLong frames = new AtomicLong();
    private volatile boolean closed;
    private volatile String closeReason;

    /** 默认写 {@code event:} 字段（浏览器按具名事件派发，前端可以 {@code addEventListener} 挑着接）。 */
    public SseWriter(OutputStream out) {
        this(out, true);
    }

    /**
     * @param namedEvents false = **不写 {@code event:} 字段**。
     *                    <p>这一条是 SSE 里很容易踩的坑：帧里一旦有 {@code event:}，
     *                    浏览器就把它当**具名事件**派发，{@code es.onmessage} <b>收不到</b>——
     *                    只能靠 {@code addEventListener('那个名字')}。
     *                    对"要看全部事件"的场景（排查用的时间线）这就成了陷阱：
     *                    得枚举所有事件名，一旦后端加了新类型，界面静默少一条。
     *                    所以那种场景要显式要求"无名帧"，让一切都走 onmessage。
     *                    （事件名并没丢——{@code data} 里本来就有 {@code type}。）
     */
    public SseWriter(OutputStream out, boolean namedEvents) {
        this.out = out;
        this.namedEvents = namedEvents;
    }

    /** 写一条 AG-UI 事件。{@code id} 用会话内 seq，浏览器会自动带成 {@code Last-Event-ID} 回传。 */
    public boolean event(SessionEvent e) {
        return frame(e.seq(), AgUiMapper.agUiType(e.type()), Json.write(AgUiMapper.toAgUi(e)));
    }

    /**
     * 写一条**已是 AG-UI 规范形状**的事件（{@code data} 就是规范 JSON 本身）。
     *
     * <p>与 {@link #event(SessionEvent)} 的区别：那条走的是本平台的信封
     * （{@code {type, sessionId, seq, payload}}），这条是给 {@code @ag-ui/client} /
     * CopilotKit 消费的标准事件。仍然带上 {@code id:}，让我们的断线续传也适用。
     */
    public boolean event(long id, String agUiType, String specJson) {
        return frame(id, agUiType, specJson);
    }

    /** 写一条自定义事件（回执、诊断），没有 seq 就不带 id。 */
    public boolean event(String type, String dataJson) {
        return frame(null, type, dataJson);
    }

    /** 注释行——心跳用。中间代理看到有字节流动就不会掐连接。 */
    public boolean comment(String text) {
        return raw(": " + text + "\n\n");
    }

    /** 原始写入（保留给"立刻回执"这类非常规帧）。 */
    public boolean raw(String text) {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            frames.incrementAndGet();
            return true;
        } catch (IOException e) {
            markClosed("write failed: " + e.getClass().getSimpleName());
            return false;
        } finally {
            lock.unlock();
        }
    }

    private boolean frame(Long id, String event, String data) {
        StringBuilder sb = new StringBuilder(96);
        if (id != null) {
            sb.append("id: ").append(id).append('\n');
        }
        if (namedEvents && event != null && !event.isBlank()) {
            sb.append("event: ").append(event).append('\n');
        }
        // data 必须是单行：Jackson 输出的 JSON 不含裸换行，这里再兜一层
        sb.append("data: ").append(data.replace("\n", "\\n")).append("\n\n");
        return raw(sb.toString());
    }

    /**
     * 主动关闭流（不关底层 socket——那由 HttpExchange 负责）。
     * 只做两件事：标记关闭、唤醒等待者。
     */
    public void close(String reason) {
        markClosed(reason);
    }

    private void markClosed(String reason) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            closeReason = reason;
            try {
                out.flush();
            } catch (IOException ignored) {
                // 对端已走，无需处理
            }
        } finally {
            lock.unlock();
            closedLatch.countDown();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public String closeReason() {
        return closeReason;
    }

    public long frameCount() {
        return frames.get();
    }

    /** 阻塞等待流关闭（断连、心跳失败或业务主动关闭都会唤醒）。 */
    public boolean awaitClosed(long millis) throws InterruptedException {
        return closedLatch.await(millis, TimeUnit.MILLISECONDS);
    }

    /** 由心跳线程调用：对端已断开时优雅收摊。 */
    public void heartbeat() {
        if (!closed) {
            comment("ping");
        }
    }
}
