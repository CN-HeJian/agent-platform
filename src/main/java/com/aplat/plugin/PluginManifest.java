package com.aplat.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件清单（U30）。一个插件 = 一个 JSON 文件，声明它提供哪些工具。
 *
 * <pre>
 * {"name":"qrcode","version":"1.0.0",
 *  "tools":[{"name":"qrcode_make","description":"把文本做成二维码",
 *            "schema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},
 *            "commandField":null,
 *            "approvalRequired":false,
 *            "handler":{"kind":"stdio","command":"python3 plugins/qrcode.py"}}]}
 * </pre>
 *
 * <h2>为什么工具实现体是一条**命令**，而参数走 stdin</h2>
 *
 * <p>另一种写法是把参数拼进命令行（{@code qrcode.py "用户输入的文本"}）。
 * 那样一旦有人把 {@code ; rm -rf /} 写进"文本"里，它就不再是参数了——
 * 这正是注入的全部含义。所以这里：命令是清单里写死的，参数以 **JSON 走 stdin**。
 * 于是"模型的输出"永远只是 stdin 里的一串字节，没有任何机会变成命令行的一部分。
 */
public record PluginManifest(
        String name,
        String version,
        String sourceFile,
        List<Tool> tools) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Tool(
            String name,
            String description,
            String schemaJson,
            String commandField,
            boolean approvalRequired,
            String command) {

        public boolean executesCommands() {
            return commandField != null && !commandField.isBlank();
        }
    }

    /**
     * 解析。任何格式错误都抛 {@link IllegalArgumentException}，
     * 消息里带**文件名**——插件目录里躺着五个文件时，"插件加载失败"没有任何意义。
     */
    public static PluginManifest parse(String sourceFile, String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(sourceFile + ": 不是合法的 JSON — " + e.getMessage());
        }
        String name = root.path("name").asText("").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException(sourceFile + ": 缺少 name");
        }
        List<Tool> tools = new ArrayList<>();
        for (JsonNode t : root.path("tools")) {
            String toolName = t.path("name").asText("").trim();
            if (toolName.isEmpty()) {
                throw new IllegalArgumentException(
                        sourceFile + ": 有一个工具没有名字（没名字的工具既调不到也卸不掉）");
            }
            JsonNode schema = t.path("schema");
            if (schema.isMissingNode() || schema.isNull()) {
                schema = MAPPER.createObjectNode().put("type", "object");
            }
            JsonNode handler = t.path("handler");
            String command = handler.path("command").asText("").trim();
            if (command.isEmpty()) {
                throw new IllegalArgumentException(
                        sourceFile + ": 工具 '" + toolName + "' 缺少 handler.command");
            }
            String kind = handler.path("kind").asText("stdio");
            if (!"stdio".equals(kind)) {
                throw new IllegalArgumentException(
                        sourceFile + ": 工具 '" + toolName + "' 的 handler.kind 只支持 stdio，收到 " + kind);
            }
            tools.add(new Tool(toolName, t.path("description").asText(toolName),
                    schema.toString(), nullIfBlank(t.path("commandField").asText("")),
                    t.path("approvalRequired").asBoolean(false), command));
        }
        if (tools.isEmpty()) {
            throw new IllegalArgumentException(sourceFile + ": 这个插件一个工具都没声明");
        }
        return new PluginManifest(name, root.path("version").asText("0.0.0"), sourceFile, tools);
    }

    private static String nullIfBlank(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    public String describe() {
        return name + "@" + version + "（" + tools.size() + " 个工具，来自 " + sourceFile + "）";
    }
}
