package com.aplat.tools;

import com.aplat.sandbox.CommandPolicy;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 默认工具策略（U15）：工具级权限 + 危险命令拦截。
 *
 * <p>两道判定，顺序有意义——便宜的、与参数无关的放前面：
 * <ol>
 *   <li><b>工具级权限</b>：白名单（非空时生效）→ 黑名单。用于"这个工具根本不该被这个
 *       部署/会话调用"。</li>
 *   <li><b>危险命令拦截</b>：只看 {@link ToolSpec#executesCommands()}，从声明的字段取值
 *       交给 {@link CommandPolicy}。**与工具叫什么名字无关**。</li>
 * </ol>
 *
 * <h2>为什么还要 lint</h2>
 * 改成"看声明"之后，唯一的失败模式变成"作者忘了声明"。这不是安全判断该猜的东西，
 * 但也不该静默漏过——所以 {@link #lint} 在<b>装配期</b>把可疑项报出来：名字像执行类、
 * 却没声明 commandField 的工具。它是给人看的告警，不是运行时的第二道猜测。
 *
 * <pre>
 *   APLAT_TOOLS_ALLOW   逗号分隔；非空时只有列出的工具可用
 *   APLAT_TOOLS_DENY    逗号分隔；列出的工具一律拒绝
 * </pre>
 */
public final class DefaultToolPolicy implements ToolPolicy {

    public static final String ENV_ALLOW = "APLAT_TOOLS_ALLOW";
    public static final String ENV_DENY = "APLAT_TOOLS_DENY";

    public static final String CODE_TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    public static final String CODE_TOOL_DENIED = "TOOL_DENIED";

    /** 名字里带这些片段的工具"看起来会执行东西"，用于装配期 lint。 */
    private static final List<String> EXEC_ISH = List.of(
            "shell", "bash", "exec", "eval", "python", "script", "command", "cmd",
            "powershell", "sudo", "run_command");

    private final Set<String> allowedTools;
    private final Set<String> deniedTools;

    public DefaultToolPolicy(Set<String> allowedTools, Set<String> deniedTools) {
        this.allowedTools = Set.copyOf(allowedTools == null ? Set.of() : allowedTools);
        this.deniedTools = Set.copyOf(deniedTools == null ? Set.of() : deniedTools);
    }

    /** 不限制任何工具（仅危险命令拦截生效）。 */
    public static DefaultToolPolicy defaults() {
        return new DefaultToolPolicy(Set.of(), Set.of());
    }

    public static DefaultToolPolicy fromEnv() {
        return fromEnv(System.getenv());
    }

    public static DefaultToolPolicy fromEnv(Map<String, String> env) {
        return new DefaultToolPolicy(csv(env.get(ENV_ALLOW)), csv(env.get(ENV_DENY)));
    }

    @Override
    public Decision check(String sessionId, ToolSpec spec, String argumentsJson) {
        if (spec == null) {
            return Decision.deny(CODE_TOOL_DENIED, "unknown tool");
        }

        // 1) 工具级权限
        if (!allowedTools.isEmpty() && !allowedTools.contains(spec.name())) {
            return Decision.deny(CODE_TOOL_NOT_ALLOWED,
                    "tool '" + spec.name() + "' is not in the allowlist " + allowedTools);
        }
        if (deniedTools.contains(spec.name())) {
            return Decision.deny(CODE_TOOL_DENIED,
                    "tool '" + spec.name() + "' is denied by policy");
        }

        // 2) 危险命令拦截——依据是声明，不是名字
        if (spec.executesCommands()) {
            String command = Json.argString(argumentsJson, spec.commandField());
            CommandPolicy.Decision decision = CommandPolicy.check(command);
            if (!decision.allowed()) {
                return Decision.deny(decision.rule(), decision.reason());
            }
        }
        return Decision.allow();
    }

    /**
     * 装配期自检：把"名字像执行类、却没声明 commandField"的工具报出来。
     *
     * <p>这类工具在运行时<b>不会</b>被危险命令检查覆盖——因为策略层无从知道哪个参数是命令。
     * 与其在运行时瞎猜，不如在启动时喊一声。
     *
     * @return 人类可读的告警，空列表 = 干净
     */
    public static List<String> lint(ToolRegistry registry) {
        List<String> warnings = new ArrayList<>();
        for (ToolSpec spec : registry.specs()) {
            if (spec.executesCommands()) {
                continue;
            }
            String name = spec.name() == null ? "" : spec.name().toLowerCase(Locale.ROOT);
            for (String token : EXEC_ISH) {
                if (name.contains(token)) {
                    warnings.add("tool '" + spec.name() + "' 名字像执行类工具，但没有声明 commandField——"
                            + "策略层无法做危险命令检查。若它确实会执行命令/脚本，请改用 ToolSpec.executing(...)。");
                    break;
                }
            }
        }
        return warnings;
    }

    public Set<String> allowedTools() {
        return allowedTools;
    }

    public Set<String> deniedTools() {
        return deniedTools;
    }

    private static Set<String> csv(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
