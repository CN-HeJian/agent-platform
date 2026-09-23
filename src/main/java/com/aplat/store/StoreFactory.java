package com.aplat.store;

import com.aplat.seam.Store;
import java.util.Map;

/**
 * 按环境变量决定用哪个 Store（U18）。
 *
 * <pre>
 *   APLAT_DB_URL       jdbc:mysql://127.0.0.1:3306/aplat   ← 设了才用数据库
 *   APLAT_DB_USER      默认 root
 *   APLAT_DB_PASSWORD  默认空
 * </pre>
 *
 * <p>三条规则：
 *
 * <p><b>1. 不设 URL 就用内存实现。</b>这是刻意的：`./mvnw -q exec:java@demo` 与 `mvn test`
 * 必须零外部依赖就能跑，否则"先起数据库才能看一眼"会毁掉本地开发体验。
 * 代价是内存模式**重启即丢**——所以启动横幅会明说当前用的是哪个。
 *
 * <p><b>2. URL 决定方言，不需要额外配置。</b>{@code jdbc:mysql:} 与 {@code jdbc:h2:} 各自识别，
 * 于是"开发 H2、部署 MySQL"只换 URL，不改代码、不加开关。
 *
 * <p><b>3. 连不上就启动失败，不要静默降级。</b>如果配了 MySQL 却连不上，悄悄退回内存实现
 * 是这里最糟的选择：服务照常起来、日志照常写，但**数据全在内存里**，
 * 直到某次重启才发现全丢了。所以让 {@link JdbcStore} 的构造直接抛。
 */
public final class StoreFactory {

    public static final String ENV_URL = "APLAT_DB_URL";
    public static final String ENV_USER = "APLAT_DB_USER";
    public static final String ENV_PASSWORD = "APLAT_DB_PASSWORD";

    private StoreFactory() {
    }

    public static Store fromEnv() {
        return fromEnv(System.getenv());
    }

    public static Store fromEnv(Map<String, String> env) {
        String url = env.get(ENV_URL);
        if (url == null || url.isBlank()) {
            return new InMemoryStore();
        }
        String user = orDefault(env.get(ENV_USER), "root");
        String password = orDefault(env.get(ENV_PASSWORD), "");
        return new JdbcStore(url.trim(), user, password);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
