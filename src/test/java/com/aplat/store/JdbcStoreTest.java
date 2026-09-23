package com.aplat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JDBC 实现跑**同一套契约**（U18）。
 *
 * <p>用 H2 的 MySQL 兼容模式跑，因为 `mvn test` 不该依赖"本机装没装 MySQL"。
 * 但这不是"用假货顶替"——H2 在这里是**真的 JDBC 引擎**：真的连、真的建表、
 * 真的并发事务、真的落盘文件。所以它验证的东西（原子取号、事务、读写往返、
 * 扩展模式）和 MySQL 上是同一套逻辑。
 *
 * <p>真 MySQL 的验收不在这层，而是一次**真实进程重启**跑出来的——
 * 记录见 DEMO-OUTPUT.md（单元测试证明不了"关掉 mysqld 再起来数据还在"）。
 */
class JdbcStoreTest extends StoreContract {

    /**
     * 每个用例一个独立的文件库。
     *
     * <p>刻意用**文件**而不是内存库：内存库证明不了"重启还在"，
     * 而持久化实现唯一值得测的就是这件事。
     */
    @TempDir
    Path tmp;

    private JdbcStore jdbc;

    /**
     * 延迟创建（而不是在 {@code @BeforeEach} 里建）。
     *
     * <p>因为 JUnit 会**先跑父类的 {@code @BeforeEach}**（它要 reset），
     * 那时子类的字段还没初始化 —— 在父类里先 reset 就会撞空指针。
     * 让"取 store"这个动作本身负责创建，顺序问题就不存在了。
     */
    private JdbcStore jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcStore(url(), "sa", "");
        }
        return jdbc;
    }

    private String url() {
        return "jdbc:h2:" + tmp.resolve("aplat")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000";
    }

    @Override
    protected Store store() {
        return jdbc();
    }

    @Override
    protected void reset() {
        jdbc().clear();
    }

    // ------------------------------------------------------------ U18 验收

    @Test
    @DisplayName("U18 验收：重启可恢复——换了实例（等价于进程重启）事件/快照/幂等键都还在")
    void survivesRestart() {
        jdbc().append(new SessionEvent("s1", 0, "input.claimed", Map.of("text", "你好"), null));
        jdbc().append(new SessionEvent("s1", 0, "turn.closed", Map.of("reason", "completed"), null));
        jdbc().saveSnapshot(new Store.Snapshot("s1", "step-2", "{\"step\":2}", null));
        jdbc().markIfAbsent("idem-1", "ref-1");

        // 模拟进程重启：同一个库，全新的实例（连接全部是新建的）
        JdbcStore restarted = new JdbcStore(url(), "sa", "");

        assertEquals(2, restarted.events("s1", 0).size(), "事件必须还在");
        assertEquals("你好", restarted.events("s1", 0).get(0).str("text"));
        assertEquals("step-2", restarted.latestSnapshot("s1").orElseThrow().stepId());
        assertEquals("ref-1", restarted.idempotentRef("idem-1").orElseThrow());

        // 最关键的一条：seq 要**接着往下走**，而不是从 1 重来。
        // 从 1 重来会让新事件和历史事件撞号，回放与续传都会错乱。
        assertEquals(3, restarted.append(new SessionEvent("s1", 0, "again", Map.of(), null)));
    }

    @Test
    @DisplayName("增量续传跨重启也成立：Last-Event-ID 不会因为重启而失效")
    void incrementalReadSurvivesRestart() {
        for (int i = 1; i <= 5; i++) {
            jdbc().append(new SessionEvent("s1", 0, "e" + i, Map.of("i", i), null));
        }
        JdbcStore restarted = new JdbcStore(url(), "sa", "");
        assertEquals(2, restarted.events("s1", 3).size(), "从 seq=3 之后应拿到 4、5");
        assertEquals(4, restarted.events("s1", 3).get(0).seq());
    }

    // ------------------------------------------------------------ 实现细节

    @Test
    @DisplayName("H2 必须显式开 MySQL 兼容模式——否则启动就报错，而不是某次 append 神秘失败")
    void h2WithoutMysqlModeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JdbcStore("jdbc:h2:" + tmp.resolve("plain") + ";DATABASE_TO_LOWER=TRUE", "sa", ""));
        assertTrue(e.getMessage().contains("MODE=MySQL"), e.getMessage());
    }

    @Test
    @DisplayName("方言从 URL 认出来，不需要额外配置（换库只换 URL）")
    void dialectIsDerivedFromUrl() {
        assertEquals("h2", jdbc().dialect());
        assertEquals("mysql", JdbcStore.dialectOf("jdbc:mysql://127.0.0.1:3306/aplat"));
        assertEquals("h2", JdbcStore.dialectOf("jdbc:h2:mem:x;MODE=MySQL"));
        assertEquals("unknown", JdbcStore.dialectOf("jdbc:postgresql://x/y"),
                "认不出来要说认不出来，别猜");
    }

    @Test
    @DisplayName("stats() 给出可核对的行数，供排查")
    void statsReportsRowCounts() {
        jdbc().append(new SessionEvent("s1", 0, "a", Map.of(), null));
        jdbc().append(new SessionEvent("s2", 0, "a", Map.of(), null));
        jdbc().saveSnapshot(new Store.Snapshot("s1", "step-1", "{}", null));
        jdbc().markIfAbsent("k", "v");

        Map<String, Object> stats = jdbc().stats();
        assertEquals("h2", stats.get("dialect"));
        assertEquals(2L, stats.get("events"));
        assertEquals(2L, stats.get("sessions"));
        assertEquals(1L, stats.get("snapshots"));
        assertEquals(1L, stats.get("idempotencyKeys"));
    }

    @Test
    @DisplayName("连不上就抛，不静默降级——静默降级会让数据全留在内存里而没人发现")
    void unreachableDatabaseFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new JdbcStore("jdbc:mysql://127.0.0.1:1/nope?connectTimeout=800", "x", "y"));
    }

    @Test
    @DisplayName("清空是幂等的（重置逻辑会被反复调用）")
    void clearIsIdempotent() {
        jdbc().clear();
        jdbc().clear();
        assertTrue(jdbc().events("s1", 0).isEmpty());
    }
}
