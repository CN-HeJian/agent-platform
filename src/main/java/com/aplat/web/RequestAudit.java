package com.aplat.web;

import java.time.Instant;

/**
 * 一条审计记录：谁、何时、调了什么、结果如何（U17）。
 *
 * <p><b>身份字段是密钥指纹，不是密钥</b>（见 {@link ApiKeyGuard#fingerprint}）。
 * 审计日志本身是"要给很多人看"的东西，往里写密钥原文等于把密钥抄送一遍。
 *
 * @param identity  调用方标识：密钥指纹或 {@code anonymous}
 * @param clientIp  对端地址。注意反向代理后面这里会是代理 IP（见 README 的说明）
 * @param status    HTTP 状态码；{@code 0} 表示处理链抛异常未及应答
 * @param note      可选备注（如限流命中的桶大小、被拒原因）
 */
public record RequestAudit(
        Instant ts,
        String identity,
        String clientIp,
        String method,
        String path,
        int status,
        long durationMs,
        String note) {

    /**
     * 脱敏后再落库（U25）。
     *
     * <p>为什么审计这里必须过一道：密钥最常见的泄露路径不是"谁打印了密钥"，
     * 而是它**随着请求本身**进了审计——{@code ?apiKey=sk-xxx} 会出现在 path 里，
     * 而路径是审计字段。做法是替换已知的密钥值，不是"猜哪些像密钥"
     * （正则猜密钥一定会漏，而漏一次就够）。
     */
    public RequestAudit redactedBy(com.aplat.auth.Secrets secrets) {
        if (secrets == null) {
            return this;
        }
        return new RequestAudit(ts, secrets.redact(identity), clientIp, method,
                secrets.redact(path), status, durationMs, secrets.redact(note));
    }

    public boolean failed() {
        return status == 0 || status >= 400;
    }

    /** 一行 JSON（JSON Lines 格式，便于 grep / 灌进任何日志系统）。 */
    public String toJsonLine() {
        return "{"
                + "\"ts\":\"" + ts + "\","
                + "\"identity\":\"" + esc(identity) + "\","
                + "\"ip\":\"" + esc(clientIp) + "\","
                + "\"method\":\"" + esc(method) + "\","
                + "\"path\":\"" + esc(path) + "\","
                + "\"status\":" + status + ","
                + "\"durationMs\":" + durationMs + ","
                + "\"note\":\"" + esc(note) + "\""
                + "}";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
