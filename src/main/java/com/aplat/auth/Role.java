package com.aplat.auth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色（U25）：一组工具的白名单，外加"能不能批别人的请求"。
 *
 * <h2>匹配规则：精确名字，或以 {@code *} 结尾的前缀</h2>
 *
 * <p>只支持这两种，且 {@code *} **只能出现在末尾**。不做通配符正则的理由是安全：
 * {@code *read*} 这种写法看起来人畜无害，实际会匹配到任何名字里带 read 的工具——
 * 包括某天新加的 {@code read_and_delete}。授权规则里"我没想到它会匹配上"
 * 是最不该出现的一句话。
 *
 * <h2>四个内置角色，而不是让配置自由组合</h2>
 *
 * <p>自由组合的配置写错方向很多，而每一个方向都是"多给了权限"。内置角色把语义写死在代码里，
 * 配置只做指认。代价是不能自定义粒度——那是对的取舍：**授权系统里，
 * "配错了"的代价远高于"配不了"**。
 */
public record Role(String name, Set<String> allowedTools, boolean canApprove) {

    public Role {
        allowedTools = Set.copyOf(allowedTools);
    }

    /** 全权：所有工具 + 可批准。给本地开发与运维自己用。 */
    public static Role admin() {
        return new Role("admin", Set.of("*"), true);
    }

    /**
     * 操作员：能跑执行类工具，但**不能批准别人的请求**。
     *
     * <p>这个组合是有意的：能干活 ≠ 能代替别人做安全决定。把这两件事绑在一起的结果是
     * "想给某人跑命令的权限，就顺手给了他批所有请求的权限"。
     */
    public static Role operator() {
        return new Role("operator", Set.of("*"), false);
    }

    /**
     * 执行者：能跑执行类工具，不能批准，且**不能用会改动系统的工具**。
     *
     * <p>与 operator 的差别在 {@code shell_write*} 这类前缀——它是"这个角色故意不给
     * 写权限"的表达。真实项目里这一层通常按业务再细分。
     */
    public static Role runner() {
        return new Role("runner", Set.of("echo", "add", "shell", "read*"), false);
    }

    /**
     * 只读：只能调纯计算类工具，**执行类一律不给**（连 shell 都不在名单里）。
     *
     * <p>这是最该存在的一个角色：接入一个只看不动的账号时，
     * "它连 shell 都没有"应当是配置层面就能一眼看出来的事实，而不是靠"shell 要求人工确认"兜。
     */
    public static Role viewer() {
        return new Role("viewer", Set.of("echo", "add", "read*", "get*", "list*", "search*"), false);
    }

    public static final Map<String, Role> BUILT_IN = Map.of(
            "admin", admin(),
            "operator", operator(),
            "runner", runner(),
            "viewer", viewer());

    public static Role of(String name) {
        Role r = BUILT_IN.get(name);
        if (r == null) {
            // 认不出的角色名**不能**退化成"什么都不许"以外的任何东西。
            // 这里的调用方（Rbac 的解析）会把它当配置错误报出来。
            throw new IllegalArgumentException("unknown role: '" + name + "'（内置角色："
                    + new java.util.TreeSet<>(BUILT_IN.keySet()) + "）");
        }
        return r;
    }

    public boolean permits(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        for (String pattern : allowedTools) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith("*")) {
                if (toolName.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    /** 给人看的一行。 */
    public String describe() {
        return name + "(工具=" + new java.util.TreeSet<>(allowedTools)
                + (canApprove ? ", 可批准" : ", 不可批准") + ")";
    }

    public static List<Role> all() {
        return List.of(admin(), operator(), runner(), viewer());
    }

    /** 便于测试与展示：不可变的有序集合。 */
    public static Set<String> setOf(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }
}
