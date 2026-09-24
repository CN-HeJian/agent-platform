package com.aplat.auth;

import com.aplat.seam.ToolSpec;
import com.aplat.tools.ToolPolicy;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按角色的工具授权（U25）。
 *
 * <pre>
 *   APLAT_RBAC="alice:admin:keyA;bob:viewer:keyB"   ← 名字:角色:密钥
 * </pre>
 *
 * <p>不配 {@code APLAT_RBAC} 时退化成"一个共享 key = 隐含 admin"，行为与 U16 完全一致——
 * 升级不该把现有部署打挂。
 *
 * <h2>为什么绑定关系由传输层登记（sessionId → Principal）</h2>
 *
 * <p>{@link ToolPolicy#check} 的签名里没有 principal。往缝里加一个参数是"更正确"的做法，
 * 但那会让每一个不需要它的实现（默认策略、测试里的 allowAll）都被迫处理一个它不关心的概念。
 *
 * <p>所以绑定关系放在这里，由传输层在收到请求时登记一次。这是一个**刻意的妥协**，
 * 它有明确的失败模式，而这里选择了**失败时关门**：没有绑定关系的会话，
 * 执行类工具一律拒绝。理由很直接——
 * 漏登记的后果如果是"放行"，那它就变成了一条绕过授权的旁路；
 * 而如果是"拒绝"，它最多是一句让人来查的报错。
 *
 * <h2>校验用的是常量时间比较</h2>
 *
 * <p>逐个 key 比会泄露"前缀对了多少"。这里沿用 {@code ApiKeyGuard} 的做法：先用 SHA-256
 * 摘一遍再比，并且失败路径与成功路径都不短路（把所有候选都比完）。
 */
public final class Rbac {

    public static final String ENV_RBAC = "APLAT_RBAC";

    /** 身份。id 是**密钥的指纹**（不是密钥本身），日志与审计里只出现它。 */
    public record Principal(String id, Role role) {

        public boolean canApprove() {
            return role.canApprove();
        }

        /** 不带 RBAC 配置时的隐含身份。 */
        public static Principal anonymousAdmin() {
            return new Principal("anonymous", Role.admin());
        }

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("principal", id);
            out.put("role", role.name());
            out.put("canApprove", role.canApprove());
            return out;
        }
    }

    private final Map<String, Principal> byKeyFingerprint = new LinkedHashMap<>();
    private final boolean enabled;
    private final Map<String, Principal> sessionPrincipals = new ConcurrentHashMap<>();
    private final java.util.List<RuntimeException> parseErrors = new java.util.ArrayList<>();

    private Rbac(Map<String, Principal> byKey, boolean enabled) {
        this.byKeyFingerprint.putAll(byKey);
        this.enabled = enabled;
    }

    /** 未配置 RBAC：单共享 key、隐含 admin。 */
    public static Rbac disabled() {
        return new Rbac(Map.of(), false);
    }

    public static Rbac fromEnv(Map<String, String> env) {
        String raw = env.get(ENV_RBAC);
        if (raw == null || raw.isBlank()) {
            return disabled();
        }
        Map<String, Principal> byKey = new LinkedHashMap<>();
        for (String entry : raw.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":", 3);
            if (parts.length != 3) {
                // 配置写错时报出来而不是跳过：跳过会让"这个人为什么访问不了"变成一场排查，
                // 而真正的问题是那一行多打了一个冒号。
                throw new IllegalArgumentException(
                        "APLAT_RBAC 的每一项必须是 名字:角色:密钥，这一项不是：" + entry.trim());
            }
            String name = parts[0].trim();
            Role role = Role.of(parts[1].trim()); // 角色名认不出来会在这里抛
            String key = parts[2].trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("APLAT_RBAC 里 " + name + " 的密钥是空的");
            }
            byKey.put(fingerprint(key), new Principal(name, role));
        }
        return new Rbac(byKey, true);
    }

    public boolean enabled() {
        return enabled;
    }

    public int principalCount() {
        return byKeyFingerprint.size();
    }

    public java.util.List<Map<String, Object>> describe() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        byKeyFingerprint.values().forEach(p -> out.add(p.view()));
        return out;
    }

    /** 从原始 key 认出身份。空 = 不认。 */
    public Optional<Principal> identify(String rawKey) {
        if (!enabled) {
            return Optional.of(Principal.anonymousAdmin());
        }
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        String fp = fingerprint(rawKey);
        // 不短路：把所有候选都比完，避免"第几个匹配上"泄露出去
        Principal found = null;
        for (Map.Entry<String, Principal> e : byKeyFingerprint.entrySet()) {
            if (constantTimeEquals(e.getKey(), fp)) {
                found = e.getValue();
            }
        }
        return Optional.ofNullable(found);
    }

    /** 传输层在收到请求时登记一次。 */
    public Principal bind(String sessionId, Principal principal) {
        if (sessionId == null || sessionId.isBlank()) {
            return principal;
        }
        sessionPrincipals.put(sessionId, principal);
        return principal;
    }

    public Optional<Principal> principalOf(String sessionId) {
        if (!enabled) {
            return Optional.of(Principal.anonymousAdmin());
        }
        return Optional.ofNullable(sessionPrincipals.get(sessionId));
    }

    /** 谁能批：配置了 RBAC 就必须有身份且角色允许；没配置时谁都能批（等价于 U12 的行为）。 */
    public boolean canApprove(String sessionId) {
        if (!enabled) {
            return true;
        }
        return principalOf(sessionId).map(Principal::canApprove).orElse(false);
    }

    // ---------------------------------------------------------------- 策略

    /**
     * 以 RBAC 为准的工具策略。**与危险命令检查组合使用**，不是替代它。
     *
     * <p>两件事各管一半：这里管"这个人能不能用这个工具"，危险命令检查管
     * "这条命令能不能跑"。只做前者的话，一个 admin 仍然会因为参数危险被拦；
     * 只做后者的话，任何登录进来的人都能跑安全范围内的命令。
     */
    public ToolPolicy policy() {
        return (sessionId, spec, argumentsJson) -> {
            if (!enabled) {
                return ToolPolicy.Decision.allow();
            }
            Optional<Principal> who = principalOf(sessionId);
            if (who.isEmpty()) {
                // 失败时关门（见类注释）。这里连纯计算工具也拒：没有身份就没有授权，
                // 而"纯计算工具反正无害"是个会随工具集变化而失效的假设。
                return ToolPolicy.Decision.deny("NO_PRINCIPAL",
                        "this session has no authenticated principal; "
                                + "the transport must bind one before any tool call");
            }
            Role role = who.get().role();
            if (!role.permits(spec.name())) {
                return ToolPolicy.Decision.deny("NOT_PERMITTED",
                        "role '" + role.name() + "' may not use tool '" + spec.name()
                                + "'（该角色的工具白名单：" + new java.util.TreeSet<>(role.allowedTools()) + "）");
            }
            return ToolPolicy.Decision.allow();
        };
    }

    // ---------------------------------------------------------------- 工具

    static String fingerprint(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 解析过程中攒下的问题（给启动横幅用）。 */
    public java.util.List<RuntimeException> parseErrors() {
        return java.util.List.copyOf(parseErrors);
    }

    /**
     * 给 {@code /whoami} 用：把授权情况摊开说清楚。
     *
     * <p>刻意分两块：{@code caller} 是"**谁在问**"（这一次请求的身份），
     * {@code principal}/{@code role} 是"**这个会话被绑给谁**"。
     * 起初我只输出了后者，于是第一次查的时候看到一片 null，反应是"RBAC 是不是没生效"——
     * 而真实情况是"这个会话还没被任何请求建立过"。两件事必须分开说。
     */
    public Map<String, Object> explain(String sessionId, java.util.List<ToolSpec> tools) {
        return explain(sessionId, tools, null);
    }

    public Map<String, Object> explain(String sessionId, java.util.List<ToolSpec> tools,
                                       Principal caller) {
        Map<String, Object> out = new LinkedHashMap<>();
        Principal p = principalOf(sessionId).orElse(null);
        out.put("rbacEnabled", enabled);
        out.put("caller", caller == null ? null : caller.id());
        out.put("callerRole", caller == null ? null : caller.role().name());
        out.put("principal", p == null ? null : p.id());
        out.put("role", p == null ? null : p.role().name());
        out.put("canApprove", canApprove(sessionId));
        if (p != null) {
            java.util.List<String> allowed = new java.util.ArrayList<>();
            java.util.List<String> denied = new java.util.ArrayList<>();
            for (ToolSpec t : tools) {
                (p.role().permits(t.name()) ? allowed : denied).add(t.name());
            }
            out.put("allowedTools", allowed);
            out.put("deniedTools", denied);
        } else {
            out.put("allowedTools", java.util.List.of());
            out.put("deniedTools", tools.stream().map(ToolSpec::name).toList());
            out.put("note", "本会话没有绑定身份：按「失败时关门」的约定，所有工具都会被拒");
        }
        return out;
    }
}
