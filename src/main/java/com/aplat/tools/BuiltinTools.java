package com.aplat.tools;

import com.aplat.seam.Tool;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;

/** 内置工具：不碰系统的那些（纯计算/字面量操作），无需沙箱也无需批准。 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    public static void registerAll(ToolRegistry registry) {
        registry.register(Tool.of(
                new ToolSpec("echo", "原样返回输入文本，用于验证链路",
                        """
                        {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
                call -> {
                    String text = Json.argString(call.argumentsJson(), "text");
                    return text == null
                            ? ToolResult.error(ToolResult.ERR_INVALID_ARGS, "missing required argument: text")
                            : ToolResult.ok(text);
                }));

        registry.register(Tool.of(
                new ToolSpec("add", "整数加法",
                        """
                        {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},
                        "required":["a","b"]}"""),
                call -> {
                    String a = Json.argString(call.argumentsJson(), "a");
                    String b = Json.argString(call.argumentsJson(), "b");
                    if (a == null || b == null) {
                        return ToolResult.error(ToolResult.ERR_INVALID_ARGS, "a and b are required");
                    }
                    try {
                        return ToolResult.ok(String.valueOf(Long.parseLong(a) + Long.parseLong(b)));
                    } catch (NumberFormatException e) {
                        return ToolResult.error(ToolResult.ERR_INVALID_ARGS, "a/b must be integers");
                    }
                }));
    }
}
