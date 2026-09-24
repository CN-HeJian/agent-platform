package com.aplat.auth;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 密钥管理的最小实现（U25）：只从环境变量读，并且**保证它不会出现在日志里**。
 *
 * <h2>为什么"不在代码里写密钥"这条不能只靠自觉</h2>
 *
 * <p>代码里不写很容易做到；难的是**写进去之后不被打出来**。密钥泄露到日志里有三种最常见的
 * 路径，而它们都不是"某人主动打印了密钥"：
 * <ol>
 *   <li>工具调用的参数里带着密钥（{@code curl -H "Authorization: Bearer sk-xxx"}），
 *       而调用参数会被审计、会被写进会话事件、会出现在前端界面上；</li>
 *   <li>异常消息里带了完整的环境变量或命令行；</li>
 *   <li>启动横幅把配置打出来"方便排查"。</li>
 * </ol>
 *
 * <p>所以这一版做的是**统一脱敏出口**：{@link #redact(String)}。
 * 审计、服务日志、错误信息都过它一道。做法是"知道哪些字符串是密钥，出现就替换掉"——
 * 而不是"猜哪些像密钥"（正则猜密钥一定会漏，而漏一次就够）。
 *
 * <h2>明文只存在于进程内存</h2>
 *
 * <p>{@link #value(String)} 拿到的明文只用于建连与建请求头，绝不进日志、不进事件、
 * 不进前端。它的调用点应当一眼可数——这是这套东西能被审计的前提。
 */
public final class Secrets {

    /** 这些环境变量的**值**会被脱敏。 */
    public static final String ENV_NAMES = "APLAT_SECRET_ENV";

    /**
     * 默认名单：本项目自己会用到的那几个。
     *
     * <p>写死默认值是刻意的——"忘了配这个变量，于是密钥进了日志"是个静默失败，
     * 而默认名单让最常见的几种不可能忘。
     */
    private static final Set<String> DEFAULT_NAMES = Set.of(
            "APLAT_API_KEY", "APLAT_LLM_API_KEY", "APLAT_DB_PASSWORD", "APLAT_RBAC");

    private static final String MASK = "***";

    private final Set<String> names = new TreeSet<>();
    private final Map<String, String> values;

    private Secrets(Set<String> names, Map<String, String> env) {
        this.names.addAll(names);
        this.values = env;
    }

    public static Secrets fromEnv(Map<String, String> env) {
        Set<String> names = new LinkedHashSet<>(DEFAULT_NAMES);
        String extra = env.getOrDefault(ENV_NAMES, "");
        for (String n : extra.split(",")) {
            if (!n.isBlank()) {
                names.add(n.trim());
            }
        }
        return new Secrets(names, env);
    }

    /** 明文取值。**调用点应当一眼可数。** */
    public String value(String name) {
        return values.get(name);
    }

    public boolean isSecret(String name) {
        return names.contains(name);
    }

    public Set<String> names() {
        return Set.copyOf(names);
    }

    /**
     * 把文本里出现过的密钥值替换成 {@code ***}。
     *
     * <p>两个刻意的做法：
     * <ul>
     *   <li><b>只替换长度 ≥ 6 的值</b>——太短的值（{@code "1"}、{@code "on"}）会在任意文本里
     *       误伤，把正常日志打成筛子，于是没人再敢看日志；</li>
     *   <li><b>连 URL 里的形态一起处理</b>：{@code password=xxx} 这种要换成
     *       {@code password=***}，所以直接对整个文本做子串替换（不需要解析 URL）。</li>
     * </ul>
     *
     * <p>另外：脱敏后的长度不保留（不写成同长度的星号）——同长度会泄露密钥长度，
     * 而那个信息对爆破是有用的。
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String name : names) {
            String secret = values.get(name);
            if (secret != null && secret.length() >= 6 && !out.contains(MASK + secret)) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }

    /** 一次脱敏多个字段。 */
    public String[] redactAll(String... parts) {
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = redact(parts[i]);
        }
        return out;
    }

    /** 给启动横幅用：只说是哪些名字，不说值。 */
    public String describe() {
        return "会脱敏的环境变量：" + names;
    }
}
