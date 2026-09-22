package com.aplat.web;

import java.util.Map;

/**
 * 传输层配置（U01 的 config 模块在 web 侧的落地）。
 *
 * <p>刻意做成不可变 record：配置在启动时定型，传输层不做运行时重配。
 * 全部可用环境变量覆盖，便于"同一份 jar，换环境只换变量"。
 *
 * <pre>
 *   APLAT_HTTP_HOST          默认 127.0.0.1（默认只听回环，避免裸奔）
 *   APLAT_HTTP_PORT          默认 8787；0 = 由系统分配（测试用）
 *   APLAT_API_KEY            未设置 = 不鉴权（仅本机自查时如此用）
 *   APLAT_SSE_HEARTBEAT_MS   默认 15000，SSE 心跳间隔，防中间层掐连接。
 *                            它同时决定**断连检测延迟**：对端断开后第一次写会"成功"
 *                            （TCP 已收到 FIN 但本地缓冲还能写），第二次才 EPIPE，
 *                            所以订阅回收最多滞后 2 个周期。要更快就把这个值调小。
 *   APLAT_CORS_ANY_ORIGIN    默认 true，只为开发态方便接前端（生产应收紧）
 * </pre>
 */
public record ServerConfig(
        String host,
        int port,
        String apiKey,
        long heartbeatMillis,
        boolean corsAnyOrigin) {

    public static final String ENV_HOST = "APLAT_HTTP_HOST";
    public static final String ENV_PORT = "APLAT_HTTP_PORT";
    public static final String ENV_API_KEY = "APLAT_API_KEY";
    public static final String ENV_HEARTBEAT = "APLAT_SSE_HEARTBEAT_MS";    public static final String ENV_CORS = "APLAT_CORS_ANY_ORIGIN";

    public static final int DEFAULT_PORT = 8787;
    public static final long DEFAULT_HEARTBEAT_MS = 15_000L;

    public ServerConfig {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        heartbeatMillis = heartbeatMillis <= 0 ? DEFAULT_HEARTBEAT_MS : heartbeatMillis;
    }

    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", DEFAULT_PORT, null, DEFAULT_HEARTBEAT_MS, true);
    }

    /** 从环境变量读取，缺省即用默认值。 */
    public static ServerConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    /** 允许注入 map，便于测试。 */
    public static ServerConfig fromEnv(Map<String, String> env) {
        return new ServerConfig(
                env.getOrDefault(ENV_HOST, "127.0.0.1"),
                intOf(env.get(ENV_PORT), DEFAULT_PORT),
                env.get(ENV_API_KEY),
                longOf(env.get(ENV_HEARTBEAT), DEFAULT_HEARTBEAT_MS),
                boolOf(env.get(ENV_CORS), true));
    }

    public ServerConfig withPort(int newPort) {
        return new ServerConfig(host, newPort, apiKey, heartbeatMillis, corsAnyOrigin);
    }

    public ServerConfig withApiKey(String newKey) {
        return new ServerConfig(host, port, newKey, heartbeatMillis, corsAnyOrigin);
    }

    public ServerConfig withHeartbeatMillis(long ms) {
        return new ServerConfig(host, port, apiKey, ms, corsAnyOrigin);
    }

    /** 是否需要鉴权：只有配了 key 才校验（完整版 RBAC 见 U25）。 */
    public boolean authEnabled() {
        return apiKey != null;
    }

    public String baseUrl() {
        String h = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port;
    }

    private static int intOf(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOf(String raw, long fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolOf(String raw, boolean fallback) {
        return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
    }
}
