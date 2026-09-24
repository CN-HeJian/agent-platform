package com.aplat.run;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单一入口（U26）：一个 jar 走所有运行模式。
 *
 * <pre>
 *   java -jar app.jar serve               起服务
 *   java -jar app.jar demo                离线演示
 *   java -jar app.jar crash u20 crash r1  崩溃恢复演示
 *   java -jar app.jar mcp-demo            MCP 端到端演示
 *   java -jar app.jar --help
 * </pre>
 *
 * <h2>为什么需要它</h2>
 *
 * <p>在此之前每个入口都是一个独立的主类，靠 Maven 的 {@code exec:java@<id>} 分别启动。
 * 那在开发时很好用（POM 里给每个入口一个 execution id），但**容器里没有 Maven**——
 * Dockerfile 里那一行 {@code java -jar app.jar serve} 需要一个会分发的入口。
 *
 * <p>于是有一个更值得问的问题：为什么不直接把 {@code Serve} 当主类？
 * 因为那样"容器里能干什么"就被写死在镜像里了，而这个 jar 在本地也要能用同样的方式跑演示。
 * 分发器让**同一个 jar** 在两种环境里行为一致。
 *
 * <h2>为什么子命令是显式的白名单</h2>
 *
 * <p>不做"反射找 main 方法"那种自动分发：白名单让 {@code --help} 能列出真正可用的东西，
 * 也让"我拼错了子命令"变成一条明确的报错。{@code ContainerAssetsTest} 会检查
 * Dockerfile 里用的子命令在这个表里——拼错的那一刻就被静态拦下，不必等到起容器。
 */
public final class Main {

    private static final Map<String, Subcommand> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("serve", args -> Serve.main(args));
        COMMANDS.put("demo", args -> Demo.main(args));
        COMMANDS.put("crash", args -> CrashDemo.main(args));
        COMMANDS.put("mcp-demo", args -> McpDemo.main(args));
    }

    /** 子命令的形参：都是 {@code main(String[])}，用一个自己的接口把 checked 异常收掉。 */
    @FunctionalInterface
    private interface Subcommand {
        void run(String[] args) throws Exception;
    }

    /** 给静态校验用：允许的子命令清单。 */
    public static java.util.Set<String> commands() {
        return java.util.Collections.unmodifiableSet(COMMANDS.keySet());
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.out.println("用法: java -jar app.jar <命令> [参数…]");
            for (String name : COMMANDS.keySet()) {
                System.out.println("  " + name);
            }
            if (args.length == 0) {
                System.exit(2);
            }
            return;
        }
        Subcommand command = COMMANDS.get(args[0]);
        if (command == null) {
            System.err.println("未知命令: '" + args[0] + "'，可用：" + COMMANDS.keySet());
            System.exit(2);
            return;
        }
        command.run(Arrays.copyOfRange(args, 1, args.length));
    }

    private Main() {
    }
}
