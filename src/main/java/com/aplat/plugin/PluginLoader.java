package com.aplat.plugin;

import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 插件加载（U30）：从目录里读 {@code *.json} 清单，把其中的工具注册进注册表。
 *
 * <h2>两条不能让步的规则</h2>
 *
 * <ol>
 *   <li><b>插件不能覆盖内置工具。</b> 一条名为 {@code shell} 的插件工具若被允许注册，
 *       它就会顶掉真正的 {@code shell}——而"顶掉"在注册表里是静默的。
 *       于是"我以为我在用一个受控的 shell"，实际是在跑某个插件给的东西。
 *       同理，两个插件给同名工具也不允许（先加载的那个赢，是个看顺序的结果）。</li>
 *   <li><b>加载失败要说清是哪个文件、为什么。</b> 插件目录里躺着五个文件，
 *       报"插件加载失败"没有意义；报"{@code qrcode.json}: 工具 x 缺少 handler.command"才有用。</li>
 * </ol>
 *
 * <h2>不加载"会跑命令"的插件工具，除非它声明了</h2>
 *
 * <p>与 MCP 是同一个道理：会执行命令的工具必须声明 {@code commandField}，
 * 否则策略层的危险命令检查整条失效。清单里给了这个字段，插件作者才能明确表达
 * "我把哪个参数当命令执行"。
 */
public final class PluginLoader {

    /** 插件目录的环境变量。 */
    public static final String ENV_PLUGINS = "APLAT_PLUGINS";

    /** 加载结果。{@code errors} 与 {@code loaded} 一样重要。 */
    public record Result(
            List<PluginManifest> loaded,
            List<String> errors,
            List<String> toolNames) {

        public boolean ok() {
            return errors.isEmpty();
        }

        public String summary() {
            if (loaded.isEmpty() && errors.isEmpty()) {
                return "没有插件";
            }
            return loaded.size() + " 个插件 / " + toolNames.size() + " 个工具"
                    + (errors.isEmpty() ? "" : "，" + errors.size() + " 个失败");
        }
    }

    private static final long TIMEOUT_SECONDS = 30;

    /**
     * 从目录加载全部插件。目录不存在返回空结果（不是错误：不配插件是常态）。
     */
    public Result load(Path dir, ToolRegistry registry) {
        if (dir == null || !Files.isDirectory(dir)) {
            return new Result(List.of(), List.of(), List.of());
        }
        List<PluginManifest> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>(builtinNames(registry));

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return new Result(List.of(), List.of(dir + ": 读不出来：" + e.getMessage()), List.of());
        }

        for (Path file : files) {
            PluginManifest manifest;
            try {
                manifest = PluginManifest.parse(file.getFileName().toString(),
                        Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                errors.add(file.getFileName() + ": " + e.getMessage());
                continue;
            }
            boolean allOk = true;
            for (PluginManifest.Tool t : manifest.tools()) {
                if (!claimed.add(t.name())) {
                    errors.add(file.getFileName() + ": 工具名 '" + t.name()
                            + "' 已被占用（内置工具不能被插件覆盖，插件之间也不允许重名）");
                    allOk = false;
                    continue;
                }
                registry.register(t.approvalRequired() || t.executesCommands()
                        ? Tool.requiringApproval(specOf(t), handlerOf(t))
                        : Tool.of(specOf(t), handlerOf(t)));
                names.add(t.name());
            }
            if (allOk) {
                loaded.add(manifest);
            }
        }
        return new Result(List.copyOf(loaded), List.copyOf(errors), List.copyOf(names));
    }

    private static List<String> builtinNames(ToolRegistry registry) {
        return registry.specs().stream().map(ToolSpec::name).toList();
    }

    private static ToolSpec specOf(PluginManifest.Tool t) {
        return t.executesCommands()
                ? ToolSpec.executing(t.name(), t.description(), t.schemaJson(), t.commandField())
                : new ToolSpec(t.name(), t.description(), t.schemaJson());
    }

    /**
     * 工具实现体：命令写死在清单里，参数以 JSON 走 **stdin**。
     *
     * <p>把参数拼进命令行是这一行代码最容易写出的版本，而它是注入本身。
     */
    private static com.aplat.seam.ToolHandler handlerOf(PluginManifest.Tool t) {
        return (ToolCall call) -> {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", t.command());
            pb.environment().put("APLAT_TOOL_NAME", t.name());
            try {
                Process p = pb.start();
                // 参数**只**走 stdin：它因此永远没有机会变成命令行的一部分
                try (java.io.OutputStream stdin = p.getOutputStream()) {
                    stdin.write((call.argumentsJson() == null ? "{}" : call.argumentsJson())
                            .getBytes(StandardCharsets.UTF_8));
                }
                String stdout = readAll(p.getInputStream());
                String stderr = readAll(p.getErrorStream());
                boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    return ToolResult.error(ToolResult.ERR_TIMEOUT,
                            "plugin '" + t.name() + "' did not finish in " + TIMEOUT_SECONDS + "s");
                }
                if (p.exitValue() != 0) {
                    String detail = stderr.isBlank() ? stdout : stderr;
                    return ToolResult.error(ToolResult.ERR_SANDBOX,
                            "plugin '" + t.name() + "' exit=" + p.exitValue() + ": " + abbreviate(detail));
                }
                return ToolResult.ok(stdout.isBlank() ? "(插件没有输出)" : abbreviate(stdout));
            } catch (IOException e) {
                return ToolResult.error(ToolResult.ERR_SANDBOX,
                        "plugin '" + t.name() + "' 无法启动：" + e.getMessage());
            }
        };
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 4000) + "…（已截断）";
    }
}
