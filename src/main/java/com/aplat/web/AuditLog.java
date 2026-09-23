package com.aplat.web;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志（U17）：谁 / 何时 / 调了什么 / 结果。
 *
 * <p>两条设计约束，都是"审计这种东西常见的翻车点"：
 *
 * <ol>
 *   <li><b>绝不拖慢（更不该拖死）请求</b>：{@link #record} 只做入队，磁盘 IO 交给一条
 *       daemon 线程。队列满了就丢并计数——宁可少一条审计，也不能让请求卡住。</li>
 *   <li><b>身份只记指纹</b>：见 {@link RequestAudit}。审计日志会被很多人看，
 *       往里写密钥原文等于把密钥抄送一遍。</li>
 * </ol>
 *
 * <p><b>取舍要说清</b>：默认只留内存环形缓冲，<b>重启即丢</b>。要持久化就设
 * {@code APLAT_AUDIT_FILE}（JSON Lines，一行一条，可直接 grep）。真要做到"不可否认"，
 * 还得进 WORM 存储/远端日志——那不在本项目范围内，但方向在这里。
 */
public final class AuditLog implements AutoCloseable {

    /** 默认保留最近多少条在内存里（够 /audit 页看现场）。 */
    public static final int DEFAULT_CAPACITY = 500;

    /** 写盘队列上限。满了就丢并计 dropped —— 审计不能反压业务。 */
    private static final int QUEUE_CAPACITY = 4_096;

    private final int capacity;
    private final Deque<RequestAudit> recent = new ArrayDeque<>();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private final Path file;
    private final BlockingQueue<RequestAudit> pending;
    private final Thread writer;
    private volatile boolean running = true;

    private AuditLog(Path file, int capacity) {
        this.capacity = Math.max(1, capacity);
        this.file = file;
        this.pending = file == null ? null : new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.writer = file == null ? null : startWriter();
    }

    /** 只留内存：重启即丢，适合本地自查。 */
    public static AuditLog inMemory() {
        return new AuditLog(null, DEFAULT_CAPACITY);
    }

    public static AuditLog inMemory(int capacity) {
        return new AuditLog(null, capacity);
    }

    /** 追加写到 JSON Lines 文件（父目录会自动创建）。 */
    public static AuditLog toFile(Path file) {
        return new AuditLog(file.toAbsolutePath(), DEFAULT_CAPACITY);
    }

    public static AuditLog toFile(Path file, int capacity) {
        return new AuditLog(file.toAbsolutePath(), capacity);
    }

    /**
     * 记一条。**不阻塞、不抛异常**——审计失败不该影响请求本身。
     */
    public void record(RequestAudit audit) {
        if (audit == null) {
            return;
        }
        total.incrementAndGet();
        synchronized (recent) {
            recent.addFirst(audit);
            while (recent.size() > capacity) {
                recent.removeLast();
            }
        }
        if (pending != null && !pending.offer(audit)) {
            dropped.incrementAndGet();
        }
    }

    /** 最近的记录（新 → 旧）。 */
    public List<RequestAudit> recent(int limit) {
        synchronized (recent) {
            List<RequestAudit> out = new ArrayList<>(Math.min(limit, recent.size()));
            int n = 0;
            for (RequestAudit a : recent) {
                if (n++ >= limit) {
                    break;
                }
                out.add(a);
            }
            return out;
        }
    }

    public long total() {
        return total.get();
    }

    /** 因写入队列满而丢弃的条数。非 0 说明审计落后于流量。 */
    public long dropped() {
        return dropped.get();
    }

    /** 落盘文件；只留内存时为 null。 */
    public Path file() {
        return file;
    }

    @Override
    public void close() {
        running = false;
        if (writer == null) {
            return;
        }
        // 刻意**不调用 interrupt()**：写线程多半正阻塞在队列的 poll 上，
        // 一打断就抛 InterruptedException 直接退出循环，**队列里还没落盘的记录会被丢掉**。
        // 关服务时丢审计是最不能接受的一种丢法，所以这里让它按自己的轮询节奏排空后自行退出。
        try {
            writer.join(2_000);
            if (writer.isAlive()) {
                // 两秒还没排空说明磁盘出了问题，此时才强断——至少把原因喊出来
                System.err.println("[audit] 写线程 2s 内未排空，放弃剩余 "
                        + (pending == null ? 0 : pending.size()) + " 条记录");
                writer.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.interrupt();
        }
    }

    private Thread startWriter() {
        Thread t = new Thread(this::drainLoop, "audit-writer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void drainLoop() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException e) {
            System.err.println("[audit] 无法创建目录 " + file.getParent() + ": " + e);
        }

        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (running || !pending.isEmpty()) {
                RequestAudit next = pending.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (next == null) {
                    out.flush();
                    continue;
                }
                out.write(next.toJsonLine());
                out.newLine();
                if (pending.isEmpty()) {
                    // 队列空了就刷一次：崩溃时最多丢最后这个批次
                    out.flush();
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("[audit] 写审计文件失败: " + e);
        }
    }
}
