package com.aplat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 同一套契约，跑在**真 MySQL** 上（U18 验收的硬证据）。
 *
 * <p>H2 那个用例证明的是"逻辑对"，这个证明的是"**在真 MySQL 上也对**"——
 * 两者都要，因为 H2 的 MySQL 兼容模式毕竟不是 MySQL：
 * 事务隔离级别、锁行为、`ON DUPLICATE KEY UPDATE` 的实现细节都可能有差别。
 *
 * <p>默认跳过（没设环境变量时），因为 `mvn test` 不该要求本机有 MySQL。跑法：
 *
 * <pre>
 *   mysql -u root -e 'CREATE DATABASE IF NOT EXISTS aplat_test CHARACTER SET utf8mb4'
 *   export APLAT_IT_DB_URL='jdbc:mysql://127.0.0.1:3306/aplat_test'
 *   export APLAT_IT_DB_USER=root
 *   ./mvnw test -Dtest=MySqlStoreTest -DfailIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>⚠️ 这个用例会**清表**（继承的 {@code reset()}），所以务必指向一个专用测试库，
 * 别指向正在用的库。
 */
@EnabledIfEnvironmentVariable(named = "APLAT_IT_DB_URL", matches = ".+",
        disabledReason = "未设 APLAT_IT_DB_URL —— 跳过真 MySQL 验收（mvn test 不该要求本机有库）")
class MySqlStoreTest extends StoreContract {

    private JdbcStore jdbc;

    private String url() {
        return System.getenv("APLAT_IT_DB_URL");
    }

    private JdbcStore jdbc() {
        if (jdbc == null) {
            String user = System.getenv().getOrDefault("APLAT_IT_DB_USER", "root");
            String pass = System.getenv().getOrDefault("APLAT_IT_DB_PASSWORD", "");
            jdbc = new JdbcStore(url(), user, pass);
        }
        return jdbc;
    }

    @Override
    protected Store store() {
        return jdbc();
    }

    @Override
    protected void reset() {
        jdbc().clear();
    }

    @Test
    @DisplayName("真 MySQL 上确认方言与实现标识（别把 H2 的绿灯当成 MySQL 的绿灯）")
    void reallyIsMysql() {
        assertEquals("mysql", jdbc().dialect());
        assertTrue(jdbc().id().contains("mysql"), jdbc().id());
    }

    @Test
    @DisplayName("U18 验收（真 MySQL）：新实例读到旧数据，且 seq 接着往下走")
    void survivesNewInstance() {
        jdbc().append(new SessionEvent("my", 0, "input.claimed", Map.of("text", "落库"), null));
        jdbc().saveSnapshot(new Store.Snapshot("my", "step-1", "{\"step\":1}", null));
        jdbc().markIfAbsent("my-idem", "ref");

        JdbcStore restarted = new JdbcStore(url(),
                System.getenv().getOrDefault("APLAT_IT_DB_USER", "root"),
                System.getenv().getOrDefault("APLAT_IT_DB_PASSWORD", ""));

        assertEquals(1, restarted.events("my", 0).size());
        assertEquals("落库", restarted.events("my", 0).get(0).str("text"));
        assertEquals("step-1", restarted.latestSnapshot("my").orElseThrow().stepId());
        assertEquals("ref", restarted.idempotentRef("my-idem").orElseThrow());
        assertEquals(2, restarted.append(new SessionEvent("my", 0, "again", Map.of(), null)));
    }
}
