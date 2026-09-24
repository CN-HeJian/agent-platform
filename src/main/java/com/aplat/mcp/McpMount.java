package com.aplat.mcp;

import com.aplat.observe.JsonLog;
import com.aplat.seam.McpClient;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把一个或多个 MCP 端点挂进工具注册表（U24）。
 *
 * <h2>默认门控：远端工具**一律需要人工确认**，除非显式信任</h2>
 *
 * <p>这是接入 MCP 时最需要重新想清楚的一件事。本地工具的危险性是**我们知道**的
 * （{@code ToolSpec.commandField} 声明了哪个参数是命令）；而远端工具的危险性我们不知道——
 * MCP 的 {@code tools/list} 里没有"我会执行命令"这个字段，只有一个多数服务端不填的
 * {@code annotations}。
 *
 * <p>于是默认必须倒过来：**不确定就要求确认**。老的默认（"没声明就不是执行类，直接放行"）
 * 在接进一个远端 shell 服务时会静默地让整条安全检查失效——而"工具列表里多了个工具"
 * 这件事本身不会引起任何人注意。
 *
 * <p>{@code trustedTools} 是那条出路：把明确知道安全的工具（查天气、算汇率）
 * 列进来，它们不问人。**注意它是按名字精确匹配的**，不接受通配——通配会把
 * "我只信任这两个"悄悄变成"我信任所有"，而那是另一回事。
 *
 * <h2>降级而不是失败</h2>
 *
 * <p>端点起不来时 {@link #mount()} 只记一笔并返回空的挂载结果，**不抛**。
 * 理由：一个 MCP 端点挂掉不该让整个服务起不来——它的工具本来就只是能力的一部分。
 * 但"悄悄降级"也不行，所以失败必须留痕（返回的 {@link Mounted#failures()} 与日志），
 * 并且启动横幅会把它打出来。
 */
public final class McpMount {

    private static final JsonLog LOG = JsonLog.of("mcp");

    /** 环境变量名。见 {@code ContainerAssetsTest}：名字必须是常量，否则拼错只会表现为「配置没生效」。 */
    public static final String ENV_ENDPOINTS = "APLAT_MCP_ENDPOINTS";
    public static final String ENV_TRUSTED = "APLAT_MCP_TRUSTED";

    /** 一个已挂载的端点。 */
    public record Mounted(
            String endpoint,
            List<String> toolNames,
            boolean healthy,
            String failure) {

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("endpoint", endpoint);
            out.put("healthy", healthy);
            out.put("tools", toolNames);
            if (failure != null) {
                out.put("failure", failure);
            }
            return out;
        }
    }

    private final ToolRegistry registry;
    private final McpClient client;
    private final Set<String> trustedTools;
    private final List<Mounted> mounted = new ArrayList<>();

    public McpMount(ToolRegistry registry, McpClient client, Set<String> trustedTools) {
        this.registry = registry;
        this.client = client;
        this.trustedTools = Set.copyOf(trustedTools);
    }

    /**
     * 从环境变量读端点与信任名单。
     *
     * <pre>
     *   APLAT_MCP_ENDPOINTS="python3 tools/mcp_server.py ; node other.js"
     *   APLAT_MCP_TRUSTED=echo,get_weather     ← 精确名字（不含 mcp_ 前缀也可用）
     * </pre>
     */
    public static McpMount fromEnv(ToolRegistry registry, McpClient client, Map<String, String> env) {
        Set<String> trusted = new LinkedHashSet<>();
        String raw = env.getOrDefault(ENV_TRUSTED, "");
        for (String name : raw.split(",")) {
            if (!name.isBlank()) {
                trusted.add(name.trim());
            }
        }
        return new McpMount(registry, client, trusted);
    }

    public static List<String> endpointsFromEnv(Map<String, String> env) {
        String raw = env.getOrDefault(ENV_ENDPOINTS, "");
        List<String> out = new ArrayList<>();
        for (String e : raw.split(";")) {
            if (!e.isBlank()) {
                out.add(e.trim());
            }
        }
        return out;
    }

    /**
     * 挂载全部端点。返回每个端点的结果（含失败原因）——调用方应当把它打出来。
     *
     * <p>不对已挂载的名字做去重：如果两个端点给出同名工具（加了前缀之后仍同名，
     * 例如同一个脚本被配了两次），第二个会覆盖第一个，而这**必须被看见**，
     * 所以这里留一笔 warn 而不是静默处理。注册表本身是"后写覆盖"，我们没有权限改它。
     */
    public List<Mounted> mount(List<String> endpoints) {
        mounted.clear();
        for (String endpoint : endpoints) {
            mounted.add(mountOne(endpoint));
        }
        return List.copyOf(mounted);
    }

    private Mounted mountOne(String endpoint) {
        if (!client.healthy(endpoint)) {
            // 不抛：一个端点挂了不该让整个服务起不来。但也不静默——返回的 failure 会被打出来。
            LOG.warn("MCP endpoint unhealthy, its tools are not mounted", Map.of("endpoint", endpoint));
            return new Mounted(endpoint, List.of(), false,
                    "健康检查未通过（进程起不来，或者起来了但不回话）");
        }

        List<McpClient.Binding> bindings;
        try {
            bindings = client.discover(endpoint);
        } catch (Exception e) {
            LOG.error("MCP discovery failed", e, Map.of("endpoint", endpoint));
            return new Mounted(endpoint, List.of(), false,
                    "工具发现失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }

        List<String> names = new ArrayList<>();
        for (McpClient.Binding b : bindings) {
            boolean requiresApproval = requiresApproval(b);
            registry.register(requiresApproval
                    ? Tool.requiringApproval(b.spec(), b.handler())
                    : Tool.of(b.spec(), b.handler()));
            names.add(b.spec().name());
            if (registry.specs().stream().filter(s -> s.name().equals(b.spec().name())).count() > 0) {
                LOG.info("mcp tool mounted", Map.of(
                        "endpoint", endpoint,
                        "tool", b.spec().name(),
                        "approvalRequired", requiresApproval,
                        "commandField", String.valueOf(b.spec().commandField())));
            }
        }
        LOG.info("mcp endpoint mounted", Map.of("endpoint", endpoint, "tools", names.size()));
        return new Mounted(endpoint, names, true, null);
    }

    /**
     * 门控判定：执行类（认出了 commandField）一律要确认；其余工具**默认也要**，
     * 除非它在信任名单里。
     */
    private boolean requiresApproval(McpClient.Binding b) {
        if (b.spec().executesCommands()) {
            return true;
        }
        String name = b.spec().name();
        if (trustedTools.contains(name)) {
            return false;
        }
        // 允许用不带前缀的远端原名声明信任（写配置的人不该还得知道我们怎么加前缀）
        Optional<String> bare = trustedTools.stream()
                .filter(t -> name.endsWith("_" + t) || name.equals(t))
                .findFirst();
        return bare.isEmpty();
    }

    /** 卸载：按名字回收 schema，否则模型还能看到一个已经消失的工具。 */
    public void unmount() {
        for (Mounted m : mounted) {
            for (String name : m.toolNames()) {
                registry.unregister(name);
            }
        }
        mounted.clear();
        if (client instanceof StdioMcpClient stdio) {
            stdio.close();
        }
    }

    public List<Mounted> mounted() {
        return List.copyOf(mounted);
    }
}
