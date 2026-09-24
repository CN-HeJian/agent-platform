package com.aplat.doc;

import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;
import com.aplat.tools.Json;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code read_document} 工具（U31）：把文档读成给模型看的文本。
 *
 * <h2>必须有一个根目录围栏，而且要用 {@code normalize().startsWith} 判断</h2>
 *
 * <p>让模型指定任意路径等于把文件系统交出去。所以这里只接受 {@code APLAT_DOCS_DIR} 里的文件。
 * 判断方式是**先 normalize 再 startsWith**——只做字符串前缀判断的话，
 * {@code docs/../../etc/passwd} 会被当成合法路径。这个坑的名字叫路径穿越，
 * 而它的写法看起来完全正常。
 *
 * <p>另外它不是"执行类工具"（不把参数当命令执行），所以不声明 commandField；
 * 但它仍然受 {@code ToolPolicy} 管（RBAC 那一级），于是"谁能读文档"也是可授权的。
 */
public final class DocumentTool {

    public static final String NAME = "read_document";

    /** 文档根目录的环境变量。不设 = 不注册这个工具。 */
    public static final String ENV_DOCS_DIR = "APLAT_DOCS_DIR";

    private DocumentTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.of(NAME, "读一个文档（txt/md、csv、json、pdf），返回给模型看的文本。"
                        + "路径必须在文档目录内。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"相对文档目录的路径，如 notes/report.md\"}},"
                        + "\"required\":[\"path\"]}");
    }

    public static Tool of(Path docsDir) {
        return Tool.of(spec(), call -> read(docsDir, call));
    }

    private static ToolResult read(Path docsDir, ToolCall call) {
        if (docsDir == null) {
            return ToolResult.error(ToolResult.ERR_BLOCKED,
                    "文档工具未启用（没有配 " + ENV_DOCS_DIR + "）");
        }
        String raw = Json.argString(call.argumentsJson(), "path");
        if (raw == null || raw.isBlank()) {
            return ToolResult.error(ToolResult.ERR_INVALID_ARGS,
                    "path is required, e.g. {\"path\":\"notes/report.md\"}");
        }
        Path root;
        Path target;
        try {
            root = docsDir.toAbsolutePath().normalize();
            // 先拼再 normalize：这样 ../ 才会在比较之前被消掉
            target = root.resolve(raw).normalize();
        } catch (InvalidPathException e) {
            return ToolResult.error(ToolResult.ERR_INVALID_ARGS, "bad path: " + raw);
        }
        if (!target.startsWith(root)) {
            // 路径穿越：**明确拒绝并说清**，不要静默读成别的文件
            return ToolResult.error(ToolResult.ERR_BLOCKED,
                    "path escapes the documents directory: '" + raw + "'。"
                            + "文档工具只接受 " + root + " 里的路径。");
        }
        if (!Files.exists(target)) {
            return ToolResult.error(ToolResult.ERR_INVALID_ARGS,
                    "no such file: " + raw + "（文档目录里的文件：" + listDir(root) + "）");
        }
        if (Files.isDirectory(target)) {
            return ToolResult.error(ToolResult.ERR_INVALID_ARGS,
                    "that is a directory, not a file: " + raw + "（里面是：" + listDir(target) + "）");
        }
        try {
            var doc = DocumentReader.read(target);
            String head = doc.summary()
                    + (doc.warnings().isEmpty() ? "" : "\n⚠ " + String.join("\n⚠ ", doc.warnings()))
                    + "\n\n";
            return ToolResult.ok(head + doc.text());
        } catch (Exception e) {
            return ToolResult.error(ToolResult.ERR_SANDBOX,
                    "read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 列一下目录里有什么：模型拼错路径时，这条信息能让它自己纠正，而不是反复猜。 */
    private static List<String> listDir(Path dir) {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().limit(30).toList();
        } catch (Exception e) {
            return List.of("(读不出来)");
        }
    }
}
