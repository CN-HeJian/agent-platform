package com.aplat.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最小 JSON-RPC 2.0 编解码（U24）。
 *
 * <p>只做 MCP 用得到的三件事：请求、响应、单行 JSON。
 *
 * <h2>为什么手写而不是引一个 JSON-RPC 库</h2>
 *
 * <p>MCP 的 stdio 传输是**一行一个 JSON**（不是 LSP 那种 {@code Content-Length} 头），
 * 请求/响应靠 {@code id} 配对，错误是一个 {@code error} 对象。全部规则就是这三句。
 * 引库要跟着它的版本走，而这里唯一会变的只有 MCP 的方法名——那是字符串，不是 API。
 *
 * <p>这也意味着**本项目对 MCP 的耦合只有一个地方**：{@link StdioMcpClient} 里那几个方法名。
 * 规范换一版时代价是三行字符串。
 */
final class JsonRpc {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 现在用的协议版本。写在常量里，是为了让它出现在 code review 的 diff 里。 */
    static final String PROTOCOL_VERSION = "2024-11-05";

    private static final AtomicLong IDS = new AtomicLong();

    private JsonRpc() {
    }

    static long nextId() {
        return IDS.incrementAndGet();
    }

    static String request(long id, String method, Object params) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", id);
        n.put("method", method);
        if (params != null) {
            n.set("params", MAPPER.valueToTree(params));
        }
        return n.toString();
    }

    /** 通知没有 id：不需要（也不该）等回复。{@code notifications/initialized} 就是这种。 */
    static String notification(String method, Object params) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        if (params != null) {
            n.set("params", MAPPER.valueToTree(params));
        }
        return n.toString();
    }

    static String result(long id, JsonNode result) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", id);
        n.set("result", result);
        return n.toString();
    }

    static String error(long id, int code, String message) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", id);
        ObjectNode err = n.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return n.toString();
    }

    /** 解析一行；不是合法 JSON 就返回 null（调用方应当忽略并记一笔，而不是崩掉）。 */
    static JsonNode parse(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }
}
