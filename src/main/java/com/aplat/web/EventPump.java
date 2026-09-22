package com.aplat.web;

import com.aplat.seam.SessionEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 回填 + 实时的事件泵：把"补齐历史"与"接上实时"缝成一条无缺无重的流。
 *
 * <p>这是 U09（断线续传）真正难的地方。天真的做法有两种，都有洞：
 * <ul>
 *   <li><b>先回填再订阅</b>：回填耗时里产生的新事件会漏掉；</li>
 *   <li><b>先订阅再回填</b>：实时事件与回填事件会交错，且重叠部分重复推送。</li>
 * </ul>
 *
 * <p>正确做法是三步，靠同一把锁把竞态关掉：
 * <ol>
 *   <li>{@link #beginBackfill(long)}：订阅先挂上，此时到达的事件只<b>攒进缓冲</b>，不写出；</li>
 *   <li>{@link #backfill(List)}：把 {@code seq > lastEventId} 的历史顺序写出；</li>
 *   <li>{@link #goLive()}：把缓冲里 {@code seq} 更大的事件补写，然后转为直写。</li>
 * </ol>
 * 逐条按 {@code seq} 去重（{@code watermark}），所以"回填区间"与"实时区间"的重叠部分不会重推。
 *
 * <p>钩子 {@link #onWritten} 在锁外触发——它可能去关流（{@code once=turn.closed}），
 * 在锁内做会造成"泵锁 → 写锁"的嵌套持有。
 */
public final class EventPump implements Consumer<SessionEvent> {

    private final SseWriter out;
    private final Deque<SessionEvent> buffer = new ArrayDeque<>();
    private long watermark;
    private boolean backfilling;
    private boolean live;
    private long written;
    private Consumer<SessionEvent> onWritten = e -> {
    };

    public EventPump(SseWriter out) {
        this.out = out;
    }

    /** 每成功写出一条事件后回调（锁外）。用于"看到某类事件就收摊"。 */
    public synchronized EventPump onWritten(Consumer<SessionEvent> hook) {
        this.onWritten = hook;
        return this;
    }

    /** 第一步（必须在 subscribe 之后调用）：开始回填，之后到达的事件进缓冲。 */
    public synchronized void beginBackfill(long afterSeq) {
        backfilling = true;
        watermark = afterSeq;
    }

    /** 第二步：顺序写出历史。 */
    public void backfill(List<SessionEvent> history) {
        List<SessionEvent> written = new ArrayList<>();
        synchronized (this) {
            for (SessionEvent e : history) {
                if (writeLocked(e)) {
                    written.add(e);
                }
            }
        }
        fire(written);
    }

    /** 第三步：转实时。缓冲里 seq 更大的事件在此补写，之后的事件直写。 */
    public void goLive() {
        List<SessionEvent> written = new ArrayList<>();
        synchronized (this) {
            for (SessionEvent e : buffer) {
                if (writeLocked(e)) {
                    written.add(e);
                }
            }
            buffer.clear();
            backfilling = false;
            live = true;
        }
        fire(written);
    }

    /** 作为 {@code SessionLog.subscribe} 的监听器。 */
    @Override
    public void accept(SessionEvent event) {
        SessionEvent hook = null;
        synchronized (this) {
            if (!live) {
                // 包括 backfilling 阶段、以及 subscribe 之后 beginBackfill 之前的极短窗口
                buffer.addLast(event);
                return;
            }
            if (writeLocked(event)) {
                hook = event;
            }
        }
        if (hook != null) {
            onWritten.accept(hook);
        }
    }

    private boolean writeLocked(SessionEvent e) {
        if (e.seq() <= watermark) {
            return false; // 已写过（回填与实时的重叠区），不重推
        }
        if (!out.event(e)) {
            return false;
        }
        watermark = e.seq();
        written++;
        return true;
    }

    private void fire(List<SessionEvent> events) {
        for (SessionEvent e : events) {
            onWritten.accept(e);
        }
    }

    public synchronized long watermark() {
        return watermark;
    }

    public synchronized long written() {
        return written;
    }

    public synchronized int buffered() {
        return buffer.size();
    }
}
