package com.aplat.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.McpClient;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.tools.DefaultToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * U24 验收：**真的起一个 MCP 服务端子进程**，用 stdio JSON-RPC 跟它说话。
 *
 * <p>为什么不在同一个 JVM 里 mock 传输层：那样验的是"我写的代码调了我写的代码"。
 * 这一层要证明的东西恰恰是跨进程的——握手、按 id 派发、对端把日志打到 stdout 时的韧性、
 * 端点起不来时的降级。这些只有真起进程才验得到。
 *
 * <p>对端用同一份代码里的 {@link StdioMcpServer}（它是主代码的一部分，不是测试替身）——
 * 于是这个测试同时是"客户端能连"与"服务端能应答"两件事的证据。
 */
class McpStdioTest {

    @TempDir
    Path tmp;

    private StdioMcpClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    /** 用当前 JVM 的 java + classpath 起 stub 服务端。 */
    private String stubCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // classpath 本身就是平台分隔符拼好的，直接用；注意别用局部变量 java 去引用 java.io.*
        // （局部变量会遮蔽包名，编译器会给出"找不到符号 io"这种让人愣一下的错）
        return java + " -cp " + System.getProperty("java.class.path")
                + " " + StdioMcpServer.class.getName();
    }

    @Test
    @DisplayName("握手 → 发现工具 → 真的调用一次（跨进程，stdio JSON-RPC）")
    void discoversAndCallsRemoteTools() {
        client = new StdioMcpClient(stubCommand());
        assertTrue(client.healthy(stubCommand()), "健康检查应当通过");

        List<McpClient.Binding> bindings = client.discover(stubCommand());
        assertEquals(2, bindings.size(), bindings.toString());
        assertTrue(bindings.stream().anyMatch(b -> b.spec().name().endsWith("_echo")));
        assertTrue(bindings.stream().anyMatch(b -> b.spec().name().endsWith("_run_command")));

        // 远端工具的名字带前缀：两个 filesystem 服务都叫 read_file 时不加前缀会静默互相覆盖
        String echoName = bindings.stream().map(b -> b.spec().name())
                .filter(n -> n.endsWith("_echo")).findFirst().orElseThrow();
        assertTrue(echoName.startsWith("mcp_"), "远端工具名必须有前缀: " + echoName);

        var binding = bindings.stream().filter(b -> b.spec().name().equals(echoName))
                .findFirst().orElseThrow();
        ToolResult result = invoke(binding, "{\"text\":\"你好\"}");
        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("echo: 你好"), result.content());
    }

    @Test
    @DisplayName("从 inputSchema 认出哪个参数是命令——不认的话远端 shell 工具会绕过危险命令检查")
    void detectsCommandFieldFromSchema() {
        client = new StdioMcpClient(stubCommand());
        List<McpClient.Binding> bindings = client.discover(stubCommand());

        var runCommand = bindings.stream().filter(b -> b.spec().name().endsWith("_run_command"))
                .findFirst().orElseThrow();
        assertTrue(runCommand.spec().executesCommands(), "run_command 必须被认成执行类");
        assertEquals("command", runCommand.spec().commandField());

        var echo = bindings.stream().filter(b -> b.spec().name().endsWith("_echo"))
                .findFirst().orElseThrow();
        assertFalse(echo.spec().executesCommands(), "echo 不该被认成执行类");
    }

    @Test
    @DisplayName("远端工具真的执行：调用 run_command 拿回命令输出")
    void callsRemoteCommandTool() throws Exception {
        client = new StdioMcpClient(stubCommand());
        var binding = client.discover(stubCommand()).stream()
                .filter(b -> b.spec().name().endsWith("_run_command"))
                .findFirst().orElseThrow();

        Path marker = tmp.resolve("remote.txt");
        ToolResult result = invoke(binding, "{\"command\":\"cat " + marker + " 2>/dev/null; echo hi\"}");
        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("hi"), result.content());
    }

    @Test
    @DisplayName("端点起不来：健康检查返回 false，不抛异常（一个端点挂了不该让服务起不来）")
    void unhealthyEndpointIsReportedNotThrown() {
        client = new StdioMcpClient("/nonexistent/definitely-not-a-program --serve");
        assertFalse(client.healthy("/nonexistent/definitely-not-a-program --serve"));
        assertTrue(client.discover("/nonexistent/definitely-not-a-program --serve").isEmpty());
    }

    @Test
    @DisplayName("对端不说话（起来了但不回话）：超时而不是永久挂住")
    void silentServerTimesOutInsteadOfHanging() {
        // 一个永远不回话的"服务端"：sleep 到你失去耐心
        client = new StdioMcpClient("sleep 30", java.time.Duration.ofMillis(300));
        long t0 = System.currentTimeMillis();
        assertFalse(client.healthy("sleep 30"));
        long ms = System.currentTimeMillis() - t0;
        assertTrue(ms < 10_000, "必须在超时上限内返回，实际 " + ms + "ms");
    }

    @Test
    @DisplayName("对端往 stdout 打日志（MCP 最经典的调试坑）不能杀掉连接")
    void noisyStdoutDoesNotKillTheConnection() throws Exception {
        // 一个先吐噪声再正常应答的服务端
        Path script = tmp.resolve("noisy.sh");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Files.writeString(script, "#!/bin/sh\n"
                + "echo 'this line is not JSON and would break a naive reader'\n"
                + "echo 'nor is this one'\n"
                + "exec " + java + " -cp '" + cp + "' " + StdioMcpServer.class.getName() + "\n");
        script.toFile().setExecutable(true);

        client = new StdioMcpClient("/bin/sh " + script);
        assertTrue(client.healthy("/bin/sh " + script), "噪声不该让健康检查失败");

        var binding = client.discover("/bin/sh " + script).stream()
                .filter(b -> b.spec().name().endsWith("_echo")).findFirst().orElseThrow();
        assertTrue(invoke(binding, "{\"text\":\"still-alive\"}").content().contains("still-alive"));
        assertFalse(client.noise().isEmpty(), "噪声应当被记下来，供排查「为什么读不到响应」");
    }

    // ---------------------------------------------------------------- 挂载

    @Test
    @DisplayName("挂载：执行类工具要确认，其余默认也要——远端工具的危险性我们并不知道")
    void mountingAppliesSafeDefaults() {
        var registry = new DefaultToolRegistry();
        var mount = new McpMount(registry, new StdioMcpClient(stubCommand()), Set.of());
        var results = mount.mount(List.of(stubCommand()));

        assertEquals(1, results.size());
        assertTrue(results.get(0).healthy());
        assertEquals(2, results.get(0).toolNames().size());

        Tool runCommand = registry.all().stream()
                .filter(t -> t.name().endsWith("_run_command")).findFirst().orElseThrow();
        assertTrue(runCommand.approvalRequired(), "执行类远端工具必须经人工确认");

        Tool echo = registry.all().stream()
                .filter(t -> t.name().endsWith("_echo")).findFirst().orElseThrow();
        assertTrue(echo.approvalRequired(),
                "没声明过的远端工具默认也要确认——「工具列表里多了个工具」本身不会引起任何人注意");

        mount.unmount();
        assertTrue(registry.all().isEmpty(), "卸载必须把 schema 一并收回");
    }

    @Test
    @DisplayName("显式信任名单让它免确认；名单按名字精确匹配，不接受通配")
    void trustedToolsSkipApproval() {
        var registry = new DefaultToolRegistry();
        // 写配置的人不该还得知道我们怎么加前缀，所以允许用远端原名
        var mount = new McpMount(registry, new StdioMcpClient(stubCommand()), Set.of("echo"));
        mount.mount(List.of(stubCommand()));

        Tool echo = registry.all().stream()
                .filter(t -> t.name().endsWith("_echo")).findFirst().orElseThrow();
        assertFalse(echo.approvalRequired(), "被显式信任的工具不该再问人");

        Tool runCommand = registry.all().stream()
                .filter(t -> t.name().endsWith("_run_command")).findFirst().orElseThrow();
        assertTrue(runCommand.approvalRequired(),
                "信任名单不是「相信这个端点」——它只信任列出来的那几个名字");
        mount.unmount();
    }

    @Test
    @DisplayName("挂载失败也留痕：返回的 failure 会被打出来，不是静默少两个工具")
    void failedMountIsVisible() {
        var registry = new DefaultToolRegistry();
        var mount = new McpMount(registry, new StdioMcpClient("/nonexistent/x"), Set.of());
        var results = mount.mount(List.of("/nonexistent/x"));

        assertFalse(results.get(0).healthy());
        assertNotNull(results.get(0).failure());
        assertTrue(results.get(0).failure().contains("健康检查未通过"));
        assertTrue(registry.all().isEmpty());
    }

    private static ToolResult invoke(McpClient.Binding binding, String args) {
        try {
            return binding.handler().handle(new ToolCall("c1", binding.spec().name(), args));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("端点列表来自环境变量，用 ; 分隔（命令里本身可能带空格）")
    void endpointsFromEnv() {
        assertEquals(List.of("python3 a.py", "node b.js"),
                McpMount.endpointsFromEnv(Map.of("APLAT_MCP_ENDPOINTS", "python3 a.py; node b.js")));
        assertTrue(McpMount.endpointsFromEnv(Map.of()).isEmpty());
    }
}
