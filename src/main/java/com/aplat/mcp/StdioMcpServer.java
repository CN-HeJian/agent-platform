package com.aplat.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 一个**极简的 MCP 服务端**（U24）：既是测试用的 stub，也是本地演示的对端。
 *
 * <p>为什么它值得放在主代码里（而不是测试目录）：验证 MCP 客户端必须有一个真的对端进程——
 * 同一个 JVM 里 mock 掉传输层，验的只是"我写的代码调了我写的代码"。
 * 这个类 100 行，能同时当测试 fixture 和 {@code exec:java@mcp-demo} 的对端。
 *
 * <p>实现的是 stdio 传输：一行一个 JSON-RPC 消息。支持三个方法：
 * {@code initialize} / {@code tools/list} / {@code tools/call}。
 *
 * <p>暴露两个工具，刻意一个有副作用、一个没有，好在演示里看出区别：
 * <ul>
 *   <li>{@code echo} —— 把文本原样返回；</li>
 *   <li>{@code run_command} —— 真的执行一条命令。<b>这不是玩具</b>：真实的 MCP
 *       filesystem / shell 服务就是干这个的，而"远端工具能执行命令"正是接入 MCP
 *       以后最需要重新想清楚的一件事（见 {@link McpMount} 的默认门控）。</li>
 * </ul>
 */
public final class StdioMcpServer {

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(
                new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        String line;
        while ((line = in.readLine()) != null) {
            JsonNode req = JsonRpc.parse(line);
            if (req == null) {
                continue; // 读不懂的行直接忽略：这是协议里"服务端不该因此退出"的要求
            }
            if (!req.hasNonNull("id")) {
                continue; // 通知：不回
            }
            long id = req.path("id").asLong();
            String method = req.path("method").asText("");
            JsonNode params = req.path("params");

            switch (method) {
                case "initialize" -> {
                    ObjectNode result = JsonRpc.MAPPER.createObjectNode();
                    result.put("protocolVersion", JsonRpc.PROTOCOL_VERSION);
                    result.putObject("capabilities").putObject("tools");
                    ObjectNode info = result.putObject("serverInfo");
                    info.put("name", "aplat-stub-mcp");
                    info.put("version", "1.0.0");
                    out.println(JsonRpc.result(id, result));
                }
                case "tools/list" -> {
                    ObjectNode result = JsonRpc.MAPPER.createObjectNode();
                    ArrayNode tools = result.putArray("tools");
                    tools.add(tool("echo", "把一段文本原样返回",
                            JsonRpc.MAPPER.createObjectNode()
                                    .put("type", "object")
                                    .<ObjectNode>set("properties", JsonRpc.MAPPER.createObjectNode()
                                            .<ObjectNode>set("text", JsonRpc.MAPPER.createObjectNode()
                                                    .put("type", "string")))
                                    .set("required", JsonRpc.MAPPER.createArrayNode().add("text"))));
                    tools.add(tool("run_command", "在服务端执行一条 shell 命令",
                            JsonRpc.MAPPER.createObjectNode()
                                    .put("type", "object")
                                    .<ObjectNode>set("properties", JsonRpc.MAPPER.createObjectNode()
                                            .<ObjectNode>set("command", JsonRpc.MAPPER.createObjectNode()
                                                    .put("type", "string")))
                                    .set("required", JsonRpc.MAPPER.createArrayNode().add("command"))));
                    out.println(JsonRpc.result(id, result));
                }
                case "tools/call" -> {
                    String name = params.path("name").asText("");
                    JsonNode callArgs = params.path("arguments");
                    out.println(JsonRpc.result(id, call(name, callArgs)));
                }
                default -> out.println(JsonRpc.error(id, -32601, "method not found: " + method));
            }
        }
    }

    private static ObjectNode tool(String name, String description, ObjectNode schema) {
        ObjectNode t = JsonRpc.MAPPER.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        t.set("inputSchema", schema);
        return t;
    }

    private static ObjectNode call(String name, JsonNode args) {
        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        switch (name) {
            case "echo" -> {
                content.add(text("echo: " + args.path("text").asText("")));
                result.put("isError", false);
            }
            case "run_command" -> {
                String command = args.path("command").asText("");
                try {
                    Process p = new ProcessBuilder("/bin/sh", "-c", command)
                            .redirectErrorStream(true).start();
                    String output = new String(p.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8).trim();
                    p.waitFor();
                    content.add(text(output.isEmpty() ? "(无输出)" : output));
                    result.put("isError", false);
                } catch (Exception e) {
                    content.add(text("命令执行失败: " + e));
                    result.put("isError", true);
                }
            }
            default -> {
                content.add(text("no such tool: " + name));
                result.put("isError", true);
            }
        }
        return result;
    }

    private static ObjectNode text(String text) {
        ObjectNode n = JsonRpc.MAPPER.createObjectNode();
        n.put("type", "text");
        n.put("text", text);
        return n;
    }
}
