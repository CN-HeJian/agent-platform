package com.aplat.seam;

/**
 * 工具执行结果。失败也必须返回结构化结果——模型要靠 errorCode 自纠，
 * 而不是让异常把整个循环炸掉。
 */
public record ToolResult(boolean ok, String content, String errorCode) {

    public static final String ERR_UNKNOWN_TOOL = "UNKNOWN_TOOL";
    public static final String ERR_INVALID_ARGS = "INVALID_ARGS";
    public static final String ERR_DENIED = "DENIED";
    public static final String ERR_BLOCKED = "BLOCKED_BY_POLICY";
    public static final String ERR_SANDBOX = "SANDBOX_FAILURE";
    public static final String ERR_TIMEOUT = "TIMEOUT";

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult error(String errorCode, String message) {
        return new ToolResult(false, message, errorCode);
    }

    public boolean failed() {
        return !ok;
    }
}
