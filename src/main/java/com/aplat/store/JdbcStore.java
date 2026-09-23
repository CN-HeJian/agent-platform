package com.aplat.store;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import com.aplat.tools.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC 持久化 Store（U18）。
 *
 * <p>同一份实现既跑 MySQL 也跑 H2——**这不是巧合，是选择的结果**：写 SQL 时刻意只用两边
 * 都支持的语法，把方言需求压到零（连 upsert 都不用 `ON DUPLICATE KEY UPDATE`，
 * 见下面的取号与快照写法）。所以"开发用 H2、部署用 MySQL"不需要两套代码，
 * 只需要换一个 URL。
 *
 * <h2>四个值得说明的取舍</h2>
 *
 * <p><b>1. 时间戳存 {@code BIGINT} 毫秒，不存 DATETIME。</b>
 * JDBC 驱动会用 **JVM 默认时区**解释 DATETIME，于是"同一行、不同时区的两台机器读出来差几小时"，
 * 而这类偏差在日志里极难发现（看起来只是时间戳有点怪）。存 epoch millis 就没这个问题，
 * 代价是不能直接用 MySQL 的日期函数查——要按日期查可以另建生成列与索引。
 *
 * <p><b>2. 取号是"一条语句原子完成"，不读 {@code MAX(seq)}。</b>
 * 读最大值再插入在并发下必然撞号。这里用 {@code INSERT ... ON DUPLICATE KEY UPDATE next_seq = next_seq + 1}：
 * 有则加一、无则建一，一个原子步骤，没有竞态窗口（详见 {@link #allocateSeq}）。
 * 这是全类**唯一**的方言假设，所以 H2 的 URL 必须带 {@code MODE=MySQL}（构造时校验）。
 *
 * <p><b>3. 每次操作开一条连接（{@link Connections}），没有连接池。</b>
 * 本地单实例够用，且省一个依赖。真正的瓶颈是先建 TCP + 认证握手，
 * 换 HikariCP 只需要替换这个 supplier —— 这是刻意留的缝。
 *
 * <p><b>4. `payload` 用 {@code MEDIUMTEXT}。</b>
 * MySQL 的 TEXT 只有 64KB，而一次工具输出（截断后 8K 字符）+ JSON 转义很容易接近它；
 * MEDIUMTEXT 是 16MB，留足余量。H2 在 MySQL 兼容模式下同样接受这个类型（实测过）。
 *
 * <p>线程安全：所有方法都是"拿连接 → 事务 → 提交/回滚"，无实例级可变状态。
 */
public final class JdbcStore implements Store, AutoCloseable {

    /** 连接来源。默认是 DriverManager（每次新开），换连接池就是替换这个 lambda。 */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 并发取号时的重试次数。撞主键/锁超时都算可重试——它们是"有人同时在做同一件事"，不是错误。 */
    private static final int MAX_RETRIES = 8;

    private static final String SQL_ENSURE_SCHEMA_SEQ = """
            CREATE TABLE IF NOT EXISTS aplat_session_seq (
              session_id VARCHAR(191) NOT NULL,
              next_seq   BIGINT       NOT NULL,
              PRIMARY KEY (session_id)
            )""";
    private static final String SQL_ENSURE_SCHEMA_EVENTS = """
            CREATE TABLE IF NOT EXISTS aplat_events (
              session_id VARCHAR(191) NOT NULL,
              seq        BIGINT       NOT NULL,
              event_type VARCHAR(96)  NOT NULL,
              payload    MEDIUMTEXT   NOT NULL,
              ts_millis  BIGINT       NOT NULL,
              PRIMARY KEY (session_id, seq)
            )""";
    private static final String SQL_ENSURE_SCHEMA_SNAPSHOTS = """
            CREATE TABLE IF NOT EXISTS aplat_snapshots (
              session_id VARCHAR(191) NOT NULL,
              step_id    VARCHAR(191) NOT NULL,
              state_json MEDIUMTEXT   NOT NULL,
              ts_millis  BIGINT       NOT NULL,
              PRIMARY KEY (session_id)
            )""";
    private static final String SQL_ENSURE_SCHEMA_IDEMPOTENCY = """
            CREATE TABLE IF NOT EXISTS aplat_idempotency (
              idem_key  VARCHAR(191) NOT NULL,
              ref       VARCHAR(512) NOT NULL,
              ts_millis BIGINT       NOT NULL,
              PRIMARY KEY (idem_key)
            )""";

    private final String url;
    private final Connections connections;

    public JdbcStore(String url, String user, String password) {
        this(url, () -> DriverManager.getConnection(url, user, password));
    }

    public JdbcStore(String url, Connections connections) {
        this.url = url;
        this.connections = connections;
        guardDialect();
        ensureSchema();
    }

    /**
     * 方言自检。**宁可启动就炸，也不要跑到一半才发现语法不兼容。**
     *
     * <p>H2 必须带 {@code MODE=MySQL} —— 取号用的 {@code ON DUPLICATE KEY UPDATE} 是 MySQL 语法，
     * 普通模式下 H2 会直接报语法错，而那个错会以"某次 append 失败"的形式出现，很难联想到 URL。
     */
    private void guardDialect() {
        String u = url == null ? "" : url.toLowerCase();
        if (u.startsWith("jdbc:h2:") && !u.contains("mode=mysql")) {
            throw new IllegalArgumentException(
                    "H2 必须用 MySQL 兼容模式（URL 里加 ;MODE=MySQL）："
                            + "JdbcStore 刻意只维护一套 SQL，其中 ON DUPLICATE KEY UPDATE 是 MySQL 语法。当前 URL: " + url);
        }
    }

    @Override
    public String id() {
        return "store.jdbc[" + dialect() + "]";
    }

    /** URL 里就能看出方言，所以不必让调用方再传一个枚举——传了反而可能不一致。 */
    public String dialect() {
        return dialectOf(url);
    }

    /** 见 {@link #dialect()}。做成静态是为了能在**不建连接**的前提下测它。 */
    public static String dialectOf(String url) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.startsWith("jdbc:mysql:")) {
            return "mysql";
        }
        if (u.startsWith("jdbc:h2:")) {
            return "h2";
        }
        return "unknown";
    }

    public String url() {
        return url;
    }

    /** 建表。幂等，启动时跑一次。 */
    public void ensureSchema() {
        try (Connection c = connections.open(); Statement s = c.createStatement()) {
            s.execute(SQL_ENSURE_SCHEMA_SEQ);
            s.execute(SQL_ENSURE_SCHEMA_EVENTS);
            s.execute(SQL_ENSURE_SCHEMA_SNAPSHOTS);
            s.execute(SQL_ENSURE_SCHEMA_IDEMPOTENCY);
        } catch (SQLException e) {
            throw new IllegalStateException("建表失败（" + id() + "）：" + e.getMessage()
                    + " —— 检查 APLAT_DB_URL/USER/PASSWORD，以及目标库是否已创建", e);
        }
    }

    // ------------------------------------------------------------------ 写

    @Override
    public long append(SessionEvent event) {
        return withRetry("append", () -> {
            try (Connection c = connections.open()) {
                c.setAutoCommit(false);
                try {
                    long seq = allocateSeq(c, event.sessionId());
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO aplat_events(session_id, seq, event_type, payload, ts_millis) "
                                    + "VALUES (?, ?, ?, ?, ?)")) {
                        ps.setString(1, event.sessionId());
                        ps.setLong(2, seq);
                        ps.setString(3, event.type());
                        ps.setString(4, Json.write(event.payload()));
                        ps.setLong(5, event.ts().toEpochMilli());
                        ps.executeUpdate();
                    }
                    c.commit();
                    return seq;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            }
        });
    }

    /**
     * 取号：**一条语句原子地"有则加一、无则建一"，再读回。**
     *
     * <p>为什么不读 {@code MAX(seq)} 再插：并发下两个事务都会读到 5、都想写 6——撞号。
     * 也不写成"锁行 → 行不存在则插行"：那在行还不存在时会留下一个真正的竞态
     * （两个事务的 gap lock 是兼容的，都会去插，其中一个必然撞主键）。
     * 用 {@code ON DUPLICATE KEY UPDATE} 就只有一个原子步骤，竞态窗口消失。
     *
     * <p>同一个事务里紧接着读回 {@code next_seq}，读到的是本事务刚写下的值。
     * 这一行会一直被锁到 append 提交为止 —— 于是**同一会话的取号天然串行**，
     * 不同会话互不影响。这正是我们要的粒度。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 是 MySQL 语法；H2 的 **MySQL 兼容模式**同样支持
     * （实测过）。整个类的方言假设只有这一处，所以 {@link #JdbcStore} 构造时会校验
     * H2 URL 带没带 {@code MODE=MySQL}。
     */
    private long allocateSeq(Connection c, String sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO aplat_session_seq(session_id, next_seq) VALUES (?, 1) "
                        + "ON DUPLICATE KEY UPDATE next_seq = next_seq + 1")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT next_seq FROM aplat_session_seq WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("取号后读不到计数器行：" + sessionId);
                }
                return rs.getLong(1);
            }
        }
    }

    @Override
    public void saveSnapshot(Snapshot snapshot) {
        this.<Void>withRetry("saveSnapshot", () -> {
            long ts = snapshot.ts() == null ? System.currentTimeMillis() : snapshot.ts().toEpochMilli();
            try (Connection c = connections.open()) {
                c.setAutoCommit(false);
                try {
                    int updated;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE aplat_snapshots SET step_id = ?, state_json = ?, ts_millis = ? "
                                    + "WHERE session_id = ?")) {
                        ps.setString(1, snapshot.stepId());
                        ps.setString(2, snapshot.stateJson());
                        ps.setLong(3, ts);
                        ps.setString(4, snapshot.sessionId());
                        updated = ps.executeUpdate();
                    }
                    if (updated == 0) {
                        // 先 UPDATE 再 INSERT，而不是 ON DUPLICATE KEY UPDATE：
                        // 后者是 MySQL 方言，用它会逼出两套 SQL（见类注释取舍 1/2）。
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO aplat_snapshots(session_id, step_id, state_json, ts_millis) "
                                        + "VALUES (?, ?, ?, ?)")) {
                            ps.setString(1, snapshot.sessionId());
                            ps.setString(2, snapshot.stepId());
                            ps.setString(3, snapshot.stateJson());
                            ps.setLong(4, ts);
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                    return null;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            }
        });
    }

    @Override
    public boolean markIfAbsent(String idempotencyKey, String ref) {
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO aplat_idempotency(idem_key, ref, ts_millis) VALUES (?, ?, ?)")) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, ref == null ? "" : ref);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                // 主键冲突就是"已经见过"——这正是幂等键要的语义，
                // 由数据库的唯一约束来保证原子性，不需要应用层加锁。
                return false;
            }
            throw new IllegalStateException("幂等键写入失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 读

    @Override
    public List<SessionEvent> events(String sessionId, long afterSeq) {
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq, event_type, payload, ts_millis FROM aplat_events "
                             + "WHERE session_id = ? AND seq > ? ORDER BY seq")) {
            ps.setString(1, sessionId);
            ps.setLong(2, afterSeq);
            try (ResultSet rs = ps.executeQuery()) {
                List<SessionEvent> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SessionEvent(sessionId, rs.getLong(1), rs.getString(2),
                            parsePayload(rs.getString(3)), Instant.ofEpochMilli(rs.getLong(4))));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读事件失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Snapshot> latestSnapshot(String sessionId) {
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT step_id, state_json, ts_millis FROM aplat_snapshots WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Snapshot(sessionId, rs.getString(1), rs.getString(2),
                        Instant.ofEpochMilli(rs.getLong(3))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读快照失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> idempotentRef(String idempotencyKey) {
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ref FROM aplat_idempotency WHERE idem_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读幂等键失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ 维护/测试

    /**
     * 清空所有数据（测试与本地重置用）。
     *
     * <p>刻意**不放进 {@link Store} 接口**：破坏性操作不该成为能力缝的一部分，
     * 否则每个实现都得提供一个"删库"方法给业务代码调用。
     */
    public void clear() {
        try (Connection c = connections.open(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM aplat_events");
            s.execute("DELETE FROM aplat_session_seq");
            s.execute("DELETE FROM aplat_snapshots");
            s.execute("DELETE FROM aplat_idempotency");
        } catch (SQLException e) {
            throw new IllegalStateException("清空失败：" + e.getMessage(), e);
        }
    }

    /** 行数快照，供健康检查与排查。 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dialect", dialect());
        out.put("events", count("aplat_events"));
        out.put("sessions", count("aplat_session_seq"));
        out.put("snapshots", count("aplat_snapshots"));
        out.put("idempotencyKeys", count("aplat_idempotency"));
        return out;
    }

    private long count(String table) {
        try (Connection c = connections.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return -1L;
        }
    }

    /** 没有连接池，所以没什么可关的——留着这个方法是给"换成池"留位置。 */
    @Override
    public void close() {
        // 见类注释取舍 3：连接即用即关，这里无事可做
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 重试包装。
     *
     * <p>只重试两类**预期内**的冲突：
     * <ul>
     *   <li>唯一约束冲突（SQLState 以 23 开头）——并发取号/建行时的正常竞争；</li>
     *   <li>锁等待超时（40001 死锁 / HYT00 超时 / 40001 序列化失败）——并发事务撞上。</li>
     * </ul>
     * 其它异常直接抛：把"表不存在"这类错误重试 8 次只会掩盖原因。
     */
    private <T> T withRetry(String what, SqlCall<T> call) {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return call.run();
            } catch (SQLException e) {
                if (!isRetryable(e)) {
                    throw new IllegalStateException(what + " 失败：" + e.getMessage(), e);
                }
                last = e;
                sleepBackoff(attempt);
            }
        }
        throw new IllegalStateException(what + " 连续冲突 " + MAX_RETRIES + " 次后放弃："
                + (last == null ? "?" : last.getMessage()), last);
    }

    private static boolean isRetryable(SQLException e) {
        String state = e.getSQLState();
        if (state == null) {
            return false;
        }
        return state.startsWith("23")      // 完整性约束（并发建行）
                || state.startsWith("40")  // 事务回滚（死锁、序列化失败）
                || "HYT00".equals(state);  // 锁等待超时
    }

    /** 唯一约束冲突的判定：MySQL 用 errorCode 1062，H2 用 SQLState 23505。 */
    static boolean isDuplicateKey(SQLException e) {
        return "23505".equals(e.getSQLState()) || e.getErrorCode() == 1062;
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(50L, 2L * attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试被中断", ie);
        }
    }

    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            // Map.copyOf 拒绝 null 值，而 errorCode 这类字段在成功路径上就是 null
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        } catch (Exception e) {
            // 事件载荷读不回来是很严重的事（回放会错），但不能因此让整次读失败
            return Map.of("_unparseable", json);
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T run() throws SQLException;
    }
}
