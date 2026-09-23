package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * U17 审计的验证：可追溯、有界、**且不泄露密钥**。
 *
 * <p>最后那条是重点。审计日志的用途决定了它会被很多人看到，
 * 往里写密钥原文等于把密钥抄送一遍——所以身份一律只存指纹。
 */
class AuditLogTest {

    private static RequestAudit audit(String identity, String path, int status) {
        return new RequestAudit(Instant.parse("2026-09-23T02:00:00Z"), identity, "127.0.0.1",
                "POST", path, status, 12L, null);
    }

    @Test
    @DisplayName("最近的记录可查，且是新 → 旧")
    void recentIsNewestFirst() {
        AuditLog log = AuditLog.inMemory(10);

        log.record(audit("a", "/run", 200));
        log.record(audit("a", "/agui/run", 200));
        log.record(audit("a", "/audit", 200));

        List<RequestAudit> recent = log.recent(10);
        assertEquals(3, recent.size());
        assertEquals("/audit", recent.get(0).path(), "最新的在最前");
        assertEquals("/run", recent.get(2).path());
        assertEquals(3, log.total());
    }

    @Test
    @DisplayName("内存只保留最近 N 条，但 total 如实累计")
    void memoryIsBounded() {
        AuditLog log = AuditLog.inMemory(3);

        for (int i = 0; i < 50; i++) {
            log.record(audit("a", "/req/" + i, 200));
        }

        assertEquals(3, log.recent(100).size(), "环形缓冲有上限");
        assertEquals(50, log.total(), "但计数不能少");
        assertEquals("/req/49", log.recent(1).get(0).path());
    }

    @Test
    @DisplayName("limit 只截取需要的条数")
    void limitIsHonoured() {
        AuditLog log = AuditLog.inMemory();

        for (int i = 0; i < 10; i++) {
            log.record(audit("a", "/p" + i, 200));
        }

        assertEquals(2, log.recent(2).size());
        assertEquals("/p9", log.recent(2).get(0).path());
    }

    @Test
    @DisplayName("落盘是 JSON Lines：一行一条，重启后能读回来")
    void persistsJsonLinesToFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/audit.jsonl");

        AuditLog log = AuditLog.toFile(file);
        log.record(audit("fingerprint1", "/run", 200));
        log.record(audit("fingerprint1", "/agui/run", 429));
        log.close();

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "两行 = 两条记录");
        assertTrue(lines.get(0).startsWith("{") && lines.get(0).endsWith("}"));
        assertTrue(lines.get(0).contains("\"path\":\"/run\""));
        assertTrue(lines.get(1).contains("\"status\":429"));
        assertEquals(file.toAbsolutePath(), log.file());
    }

    @Test
    @DisplayName("close() 必须把队列排空——关服务时丢审计是最不能接受的丢法")
    void closeDrainsPendingRecords(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.jsonl");
        AuditLog log = AuditLog.toFile(file);

        for (int i = 0; i < 200; i++) {
            log.record(audit("a", "/p" + i, 200));
        }
        log.close(); // 立刻关，不给写线程喘息

        assertEquals(200, Files.readAllLines(file).size(),
                "close() 不能丢掉还没落盘的记录（早先的实现 interrupt 了写线程，队列直接被丢弃）");
    }

    @Test
    @DisplayName("绝不记录密钥原文——身份只留指纹")
    void neverStoresRawKey() throws Exception {
        String rawKey = "sk-super-secret-value";

        // 指纹本身不可反推、也不含原文
        String fingerprint = ApiKeyGuard.fingerprint(rawKey);
        assertFalse(fingerprint.contains("super"), "指纹不该包含原文片段");
        assertFalse(fingerprint.contains("sk-"), "指纹不该保留任何原文特征");
        assertEquals(8, fingerprint.length(), "SHA-256 前 4 字节 = 8 位十六进制");
        assertEquals(fingerprint, ApiKeyGuard.fingerprint(rawKey), "同一密钥指纹必须稳定");
        assertNotEquals(fingerprint, ApiKeyGuard.fingerprint(rawKey + "x"), "不同密钥必须不同");

        RequestAudit record = new RequestAudit(Instant.now(), fingerprint, "127.0.0.1",
                "POST", "/agui/run", 200, 5L, "ok");
        assertFalse(record.toJsonLine().contains(rawKey), "整行 JSON 里不该出现密钥");
    }

    @Test
    @DisplayName("JSON 行会转义引号与控制字符，不会把一行撑成两行")
    void jsonLineEscapesControlCharacters() {
        RequestAudit record = new RequestAudit(Instant.now(), "id\"with\"quotes", "127.0.0.1",
                "GET", "/a\nb\tc", 200, 1L, "换行也要转义\n否则这就成了两条");

        String line = record.toJsonLine();

        assertEquals(0, line.chars().filter(c -> c == '\n' || c == '\r').count(),
                "一行就是一条记录，行内不能有真实换行");
        assertTrue(line.contains("\\\""), "引号要转义");
        assertTrue(line.contains("\\n"), "换行要转义");
    }

    @Test
    @DisplayName("failed() 认 4xx/5xx 与「未及应答」（status=0）")
    void failedFlag() {
        assertFalse(audit("a", "/p", 200).failed());
        assertTrue(audit("a", "/p", 401).failed());
        assertTrue(audit("a", "/p", 429).failed());
        assertTrue(audit("a", "/p", 500).failed());
        assertTrue(audit("a", "/p", 0).failed(), "处理链抛异常没来得及应答，也必须算失败");
    }

    @Test
    @DisplayName("只留内存时不写文件，close 也不该报错")
    void memoryOnlyHasNoFile() {
        AuditLog log = AuditLog.inMemory();

        assertTrue(log.file() == null);
        assertEquals(0, log.dropped());
        log.record(null);
        assertEquals(0, log.total(), "null 记录直接忽略");
        log.close();
    }
}
