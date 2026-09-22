package com.aplat.tools;

import com.aplat.seam.Sandbox;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;

/**
 * shell 工具：唯一真正碰系统的工具，且**必须**经 {@link Sandbox} 执行。
 *
 * <p>注意构造注入：工具不自己去 new 沙箱，也不查全局变量——换沙箱实现时这里零改动。
 */
public final class ShellTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final Sandbox sandbox;

    public ShellTool(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Tool build() {
        ToolSpec spec = new ToolSpec(
                "shell",
                "在隔离沙箱中执行 shell 命令并返回 stdout。工作目录为 /work，无网络。",
                """
                {"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"}},
                "required":["command"]}""");
        return Tool.requiringApproval(spec, call -> {
            String command = Json.argString(call.argumentsJson(), "command");
            if (command == null || command.isBlank()) {
                return ToolResult.error(ToolResult.ERR_INVALID_ARGS, "missing required argument: command");
            }
            var result = sandbox.exec(com.aplat.seam.ExecRequest.shell(command));
            if (result.timedOut()) {
                return ToolResult.error(ToolResult.ERR_TIMEOUT,
                        "command timed out; partial output:\n" + truncate(result.stdout()));
            }
            String body = truncate(result.stdout());
            if (!result.success()) {
                return ToolResult.error(ToolResult.ERR_SANDBOX,
                        "exit=" + result.exitCode() + "\nstdout:\n" + body + "\nstderr:\n" + truncate(result.stderr()));
            }
            return ToolResult.ok(body.isEmpty() ? "(no output)" : body);
        });
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS)
                + "\n... [truncated " + (s.length() - MAX_OUTPUT_CHARS) + " chars]";
    }
}
