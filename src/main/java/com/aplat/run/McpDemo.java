package com.aplat.run;

import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.mcp.McpMount;
import com.aplat.mcp.StdioMcpClient;
import com.aplat.mcp.StdioMcpServer;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.Store;
import com.aplat.seam.Tool;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.seam.SessionLog;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.DefaultToolPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 接入的端到端演示（U24）：模型发起调用 → 工具管线 → 人工确认 → **远端子进程**真执行。
 *
 * <pre>
 *   ./mvnw -q compile exec:java@mcp-demo
 * </pre>
 *
 * <p>对端就是同一份代码里的 {@link StdioMcpServer}（一个极简 MCP 服务端），
 * 用当前 JVM 的 java 起一个子进程跟它说 stdio JSON-RPC。
 *
 * <p>这里刻意让模型**调两次**：一次是被信任的 {@code echo}（免确认），
 * 一次是执行类的 {@code run_command}。两次都走同一条管线，差别只在门控——
 * 而那个差别正是接入远端工具时最需要看清楚的东西。
 */
public final class McpDemo {

    public static void main(String[] args) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String endpoint = java + " -cp " + System.getProperty("java.class.path")
                + " " + StdioMcpServer.class.getName();

        System.out.println("=== MCP 演示 ===");
        System.out.println("对端命令 : " + endpoint);
        System.out.println();

        Store store = new InMemoryStore();
        List<McpMount> mounts = new ArrayList<>();
        List<McpMount.Mounted> results = new ArrayList<>();

        // 前缀显式给：从命令行猜出来的前缀会跟着命令行变化，而工具名是要写进配置的东西。
        // 本演示里它也让脚本化的模型能直接写出工具名（mcp_demo_echo / mcp_demo_run_command）。
        StdioMcpClient client = new StdioMcpClient(endpoint,
                StdioMcpClient.DEFAULT_REQUEST_TIMEOUT, "demo");
        String echoTool = client.toolNamePrefix() + "echo";
        String runTool = client.toolNamePrefix() + "run_command";

        // 脚本化模型：先调 echo（被信任，免确认），再调 run_command（执行类，要确认）
        ScriptedLlmAdapter llm = new ScriptedLlmAdapter()
                .thenToolCall(echoTool, "{\"text\":\"来自模型的问候\"}")
                .thenToolCall(runTool, "{\"command\":\"echo 我是远端子进程，pid=$$\"}")
                .thenText("两个远端工具都调过了。");

        Platform platform = Platform.assemble(llm, new ProcessSandbox(), LoopBudget.defaults(),
                DefaultToolPolicy.defaults(), store, log -> Hitl.autoAllow(), reg -> {
                    McpMount mount = new McpMount(reg, client,
                            Set.of("echo")); // 只信任 echo；run_command 必须走确认
                    results.addAll(mount.mount(List.of(endpoint)));
                    mounts.add(mount);
                });

        for (McpMount.Mounted m : results) {
            System.out.println("挂载结果 : " + (m.healthy() ? "成功，挂上 " + m.toolNames().size() + " 个工具"
                    : "失败：" + m.failure()));
            if (!m.healthy()) {
                // 这条提示是踩出来的：exec:java 下 java.class.path 是 Maven 的 classworlds，
                // 拿不到项目类路径，于是"用当前 JVM 起子进程"必然失败。
                System.out.println("           （若在 exec:java 下运行，请改用 exec:exec@mcp-demo：");
                System.out.println("             exec:java 的 java.class.path 是 Maven 自己的 classloader，"
                        + "不含项目类）");
            }
            for (String name : m.toolNames()) {
                Tool t = platform.tools().find(name).orElseThrow();
                System.out.println("  " + name
                        + "  commandField=" + String.valueOf(t.spec().commandField())
                        + "  需确认=" + t.approvalRequired());
            }
        }
        System.out.println();

        var task = platform.durable().start(platform.durable().submit("s-mcp", "调两个远端工具").taskId());
        System.out.println("任务结果 : " + task.state() + " / " + task.detail());
        System.out.println();

        var log = (EventSourcedSessionLog) platform.sessionLog();
        System.out.println("--- 事件流（工具部分）---");
        for (var e : log.ofType("s-mcp", SessionLog.EV_TOOL_RESULT)) {
            System.out.println("  " + e.type() + " tool=" + e.str("tool")
                    + " ok=" + e.str("ok") + " content=" + e.str("content"));
        }
        System.out.println();
        System.out.println("--- 追踪（投影出来的 span 树）---");
        var trace = com.aplat.observe.TraceBuilder.build(platform.sessionLog(), "s-mcp");
        for (var span : trace.byKind("tool")) {
            System.out.println("  " + span.name() + "  " + span.durationMillis() + "ms  "
                    + (span.ended() ? "已结束" : "开口"));
        }
        System.out.println("  人机等待 span 数 = " + trace.byKind("hitl").size() + "（auto-allow 下为 0 条"
                + "「等待」，但执行类工具的放行仍会留痕）");
        System.out.println();
        System.out.println("会话事件总数 = " + trace.events().size()
                + "，开口 span = " + trace.openSpans());

        mounts.forEach(McpMount::unmount);
        System.out.println("已卸载：" + platform.tools().all().size() + " 个工具残留"
                + "（MCP 工具都收回了）");
        platform.close();
    }

    private McpDemo() {
        // 让 checkstyle 之外的读者也知道这是入口类
        Map.of();
    }
}
