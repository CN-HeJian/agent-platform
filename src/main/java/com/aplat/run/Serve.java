package com.aplat.run;

import com.aplat.auth.Rbac;
import com.aplat.auth.Secrets;
import com.aplat.hitl.InteractiveHitl;
import com.aplat.hitl.ScopedHitl;
import com.aplat.mcp.McpMount;
import com.aplat.mcp.StdioMcpClient;
import com.aplat.plugin.PluginLoader;
import com.aplat.llm.OpenAiCompatibleAdapter;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.sandbox.DockerSandbox;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Store;
import com.aplat.auth.Role;
import com.aplat.seam.Sandbox;
import com.aplat.store.StoreFactory;
import com.aplat.tools.DefaultToolPolicy;
import com.aplat.web.HttpTransport;
import com.aplat.web.ServerConfig;
import com.aplat.web.UiAssets;
import java.util.ArrayList;
import java.util.List;

/**
 * 起服务：把薄内核挂到 HTTP + SSE 上（U02 的入口）。
 *
 * <pre>
 *   # 离线（脚本化模型，零配置）
 *   ./mvnw -q compile exec:java@serve
 *
 *   # 真实模型（任何 OpenAI 兼容服务）
 *   export APLAT_LLM_BASE_URL=https://api.deepseek.com/v1
 *   export APLAT_LLM_API_KEY=sk-xxx
 *   export APLAT_LLM_MODEL=deepseek-chat
 *   ./mvnw -q compile exec:java@serve
 *
 *   # 换了端口 / 需要带 key
 *   export APLAT_HTTP_PORT=8080
 *   export APLAT_API_KEY=dev-secret
 *
 *   # 人工确认（U12）。默认就是 ask：能执行命令的服务，"先问"才是诚实的默认值
 *   export APLAT_HITL=ask              # allow = 不问人直接放行；deny = 直接拒绝
 *   export APLAT_HITL_TIMEOUT_SEC=120  # 无人应答的等待上限
 *
 *   # 按角色授权（U25）。不配 = 一个共享 key = 隐含 admin
 *   export APLAT_RBAC="alice:admin:keyA;bob:viewer:keyB"
 *   export APLAT_SECRET_ENV="MY_OTHER_TOKEN"   # 额外要脱敏的环境变量（默认已含 API key 等）
 *   curl -s -H "X-API-Key: keyB" "$BASE/whoami?sessionId=s1"   # 看某个会话的实际授权
 *
 *   # MCP 接入（U24）。端点是一行命令，多个用 ; 分隔
 *   export APLAT_MCP_ENDPOINTS="python3 tools/mcp_server.py"
 *   export APLAT_MCP_TRUSTED=echo,get_weather   # 免确认名单（精确名字，不接通配）
 *
 *   # 持久化（U18）。不设 = 内存，重启即丢
 *   export APLAT_DB_URL=jdbc:mysql://127.0.0.1:3306/aplat
 *   export APLAT_DB_USER=root
 *   export APLAT_DB_PASSWORD=
 * </pre>
 *
 * 注意用 {@code exec:java@serve} 而不是 {@code -Dexec.mainClass}：POM 里显式配置的
 * {@code mainClass} 优先级高于用户属性，要让 {@code -D} 生效得先把它从 configuration 里删掉。
 * 两个入口做成了两个 execution id（{@code demo} / {@code serve}），互不干扰。
 *
 * 默认只监听 127.0.0.1：这个服务能执行 shell，不该在你不注意的时候对外可达。
 */
public final class Serve {

    /** 一行说清"数据存在哪、重启会不会丢"——这是本地开发最容易踩的认知坑。 */
    private static String describeStore(Store store) {
        if (store instanceof com.aplat.store.JdbcStore jdbc) {
            String where = jdbc.url().replaceAll("(?i)(password=)[^;&]*", "$1***");
            return jdbc.id() + "  ← 重启不丢（" + where + "）";
        }
        return store.id() + "  ← ⚠ 重启即丢；要持久化就设 APLAT_DB_URL";
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.fromEnv();
        boolean realModel = OpenAiCompatibleAdapter.envConfigured();

        Sandbox sandbox = DockerSandbox.defaultImage().available()
                ? DockerSandbox.defaultImage()
                : new ProcessSandbox();

        LlmAdapter llm = realModel
                ? OpenAiCompatibleAdapter.fromEnv()
                // 离线模式给一段固定剧本，便于"不打网络也能看到完整流式链路"。
                // repeat()：服务是要被反复戳的，脚本不该第二次请求就"用尽"。
                : new ScriptedLlmAdapter()
                        .thenToolCall("shell", "{\"command\":\"echo hello-from-http\"}")
                        .thenText("已在沙箱中执行命令，输出为 hello-from-http。")
                        .repeat();

        // 持久化：配了 APLAT_DB_URL 就用 JDBC（MySQL 或 H2），没配就内存。
        // 注意这里会**真的连一次库**（JdbcStore 构造时建表）——连不上就启动失败，
        // 因为"配了库却悄悄退回内存"是最糟的结果：跑得好好的，直到重启才发现数据全丢了。
        Store store = StoreFactory.fromEnv();

        // MCP 接入（U24）：端点来自环境变量，**一个端点一个子进程**（stdio 传输天然如此）。
        // 挂载失败不抛异常——一个 MCP 端点挂掉不该让服务起不来——但结果会被打出来。
        List<String> mcpEndpoints = McpMount.endpointsFromEnv(System.getenv());
        List<McpMount> mounts = new ArrayList<>();
        List<McpMount.Mounted> mcpResults = new ArrayList<>();

        // HITL 走工厂：InteractiveHitl 要把 hitl.requested 写进会话日志，
        // 而日志是内核建的——所以它只能在装配过程里被创建（见 Platform 的注释）。
        // 外面再套一层 ScopedHitl：定时任务可以带一份"提前批准"的豁免名单（U22），
        // 而那份名单只在那条调度自己的线程上有效。不套的话，调度里的"提前批准"根本无处生效。
        // RBAC（U25）：配了 APLAT_RBAC 就按角色授权，没配就退化成单共享 key（行为与 U16 一致）。
        // 解析失败**直接退出**：一个"我没看懂你的授权配置"的服务，比一个"我用默认配置跑起来了"
        // 的服务危险得多——后者会让所有人都是 admin。
        Rbac rbac;
        try {
            rbac = Rbac.fromEnv(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.err.println("          格式：APLAT_RBAC=名字:角色:密钥;名字:角色:密钥");
            System.err.println("          内置角色：" + Role.all().stream().map(Role::describe).toList());
            System.exit(2);
            return;
        }
        Secrets secrets = Secrets.fromEnv(System.getenv());

        // 组合策略：RBAC 管"这个人能不能用这个工具"，默认策略管"这条命令能不能跑"。
        // 两者都必须过——只做前者的话 admin 仍会被危险命令拦，只做后者的话谁都能跑。
        com.aplat.tools.ToolPolicy policy = rbac.enabled()
                ? DefaultToolPolicy.fromEnv().and(rbac.policy())
                : DefaultToolPolicy.fromEnv();

        // 插件（U30）：也是装配期挂工具。与 MCP 的区别是"本地进程 + stdin 传参"，
        // 不需要远端服务，也不需要子进程常驻。
        String pluginDir = System.getenv(PluginLoader.ENV_PLUGINS);
        PluginLoader plugins = new PluginLoader();
        java.util.List<String> pluginErrors = new java.util.ArrayList<>();

        Platform platform = Platform.assemble(llm, sandbox, LoopBudget.defaults(),
                policy, store,
                log -> new ScopedHitl(InteractiveHitl.fromEnv(log, System.getenv()), log),
                reg -> {
                    for (String endpointCommand : mcpEndpoints) {
                        McpMount mount = McpMount.fromEnv(reg,
                                new StdioMcpClient(endpointCommand), System.getenv());
                        mcpResults.addAll(mount.mount(List.of(endpointCommand)));
                        mounts.add(mount);
                    }
                    if (pluginDir != null && !pluginDir.isBlank()) {
                        var result = plugins.load(java.nio.file.Path.of(pluginDir), reg);
                        pluginErrors.addAll(result.errors());
                    }
                });

        // 启动顺序（U22）：先认领孤儿，再开调度。
        // 反过来的话，一条上次崩在跑的任务会被调度器看成"还在跑"，
        // 于是那条调度被白白跳过一整轮——而原因在任何日志里都看不到。
        int reclaimed = platform.durable().reclaimOrphans();
        platform.scheduler().start();

        System.out.println("=== 装配清单 ===");
        System.out.println(platform.assemblyReport());
        for (String warning : platform.policyWarnings()) {
            System.out.println("[policy][warn] " + warning);
        }
        System.out.println("LLM     : " + llm.id() + (realModel ? "（真实服务）" : "（离线脚本）"));
        System.out.println("Sandbox : " + sandbox.description());
        System.out.println("HITL    : " + platform.hitl().id());
        System.out.println("Store   : " + describeStore(store));
        for (McpMount.Mounted m : mcpResults) {
            System.out.println("MCP     : " + (m.healthy()
                    ? "已挂载 " + m.toolNames().size() + " 个工具 ← " + m.endpoint()
                    : "⚠ 未挂载（" + m.failure() + "）← " + m.endpoint()));
        }
        if (mcpEndpoints.isEmpty()) {
            System.out.println("MCP     : 未配置（设 APLAT_MCP_ENDPOINTS=<一行命令> 接入，见 README）");
        }
        System.out.println("耐久    : 任务 "
                + platform.durable().list().size() + " 条"
                + (reclaimed > 0 ? "，启动时认领孤儿 " + reclaimed + " 条（可续跑）" : "")
                + "；调度 " + platform.scheduler().list().size() + " 条（每 "
                + platform.scheduler().tickMillis() / 1000 + "s 扫一次）");

        HttpTransport transport = new HttpTransport(platform, config, UiAssets.fromEnv(),
                rbac, secrets).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            transport.close();
            mounts.forEach(McpMount::unmount); // 先卸 MCP：它们的子进程由我们负责收摊
            platform.close(); // 关掉可关闭的组件（现在是 JDBC store，将来是连接池）
        }, "shutdown"));

        if (rbac.enabled()) {
            System.out.println("授权    : 按角色（" + rbac.principalCount() + " 个身份）");
            for (var row : rbac.describe()) {
                System.out.println("          " + row.get("principal") + " → " + row.get("role")
                        + (Boolean.TRUE.equals(row.get("canApprove")) ? "（可批准）" : "（不可批准）"));
            }
        } else {
            System.out.println("授权    : 单一共享 key（未配 " + Rbac.ENV_RBAC
                    + "）；配了它就按角色授权");
        }
        System.out.println("密钥    : " + secrets.describe());
        System.out.println("插件    : " + (pluginDir == null || pluginDir.isBlank()
                ? "未配置（设 " + PluginLoader.ENV_PLUGINS + "=<目录> 接入，见 plugins/README.md）"
                : "目录 " + pluginDir));
        for (String e : pluginErrors) {
            System.out.println("          ⚠ " + e);
        }

        String base = transport.baseUrl();
        System.out.println();
        System.out.println("=== 已启动 " + base + " ===");
        if (config.authEnabled()) {
            System.out.println("鉴权    : X-API-Key 已启用（/health 与 /ui/ 静态资源豁免）");
        } else {
            System.out.println("鉴权    : 未启用（仅监听 " + config.host() + "；对外暴露前请设 APLAT_API_KEY）");
        }
        if (ScopedHitl.unwrap(platform.hitl()) instanceof InteractiveHitl hitl) {
            String keyArg = config.authEnabled() ? " -H 'X-API-Key: <你的key>'" : "";
            if (hitl.mode() == InteractiveHitl.Mode.ASK) {
                System.out.println("人工确认: 已开启 —— shell 这类工具执行前会停下来等人");
                System.out.println("          网页上点批准，或者命令行：");
                System.out.println("            curl -s" + keyArg + " " + base + "/hitl/pending");
                System.out.println("            curl -s -X POST" + keyArg + " " + base + "/hitl/<requestId> \\");
                System.out.println("                 -H 'Content-Type: application/json' -d '{\"decision\":\"once\"}'");
                System.out.println("          " + hitl.timeout().toSeconds()
                        + "s 内没人应答按超时处理（与「拒绝」不同：模型会被告知「人不在」）");
                System.out.println("          本地想跳过这道门：APLAT_HITL=allow");
            } else {
                System.out.println("人工确认: " + hitl.mode().name().toLowerCase()
                        + "（不问人，但仍会在会话日志里记一条 hitl.auto_approved）");
            }
        }
        System.out.println();
        System.out.println("前端会话面 : " + base + "/ui/         ← CopilotKit（U07a）");
        System.out.println("调试控制台 : " + base + "/            ← 自带的最小控制台");
        System.out.println("AG-UI 端点 : POST " + base + "/agui/run   ← 前端接的就是它");
        transport.uiAssetsHint().ifPresent(hint -> System.out.println("[ui][warn] " + hint));
        System.out.println();
        System.out.println("健康检查   : curl -s " + base + "/health");
        System.out.println("同步跑一次 : curl -s -X POST " + base + "/run \\");
        System.out.println("               -H 'Content-Type: application/json' \\");
        System.out.println("               -d '{\"input\":\"用 shell 打印当前目录\"}'");
        System.out.println("AG-UI 手测 : curl -N -X POST " + base + "/agui/run \\");
        System.out.println("               -H 'Content-Type: application/json' \\");
        System.out.println("               -d '{\"threadId\":\"t1\",\"runId\":\"r1\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}'");
        System.out.println("简化流     : curl -N '" + base + "/agui/stream?input=hello'（自带控制台用）");
        System.out.println("续传       : curl -N '" + base
                + "/agui/events/<sessionId>?lastEventId=7&once=turn.closed'");
        System.out.println();
        System.out.println("按 Ctrl+C 停止。");

        // 常驻：HttpServer 自己跑在后台线程池上，主线程只需挂住
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
