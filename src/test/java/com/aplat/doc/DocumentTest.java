package com.aplat.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * U31 验收：文档解析。
 *
 * <p>最该看的两条：**路径穿越被挡住**（`../../etc/passwd` 读不成），
 * 以及**不确定的时候给警告而不是假装成功**（PDF 抠不出文字时明确说「可能是扫描件」）。
 */
class DocumentTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ 各格式

    @Test
    @DisplayName("txt/md：原样读出来")
    void plainText() throws Exception {
        Path f = tmp.resolve("note.md");
        Files.writeString(f, "# 标题\n正文一行\n", StandardCharsets.UTF_8);
        var doc = DocumentReader.read(f);
        assertEquals("text", doc.kind());
        assertTrue(doc.text().contains("# 标题"));
        assertTrue(doc.warnings().isEmpty());
    }

    @Test
    @DisplayName("csv：带上字段名，而不是让模型去数列数")
    void csvBecomesLabelledRows() throws Exception {
        Path f = tmp.resolve("sales.csv");
        Files.writeString(f, "month,amount,note\n1月,\"1,200\",含逗号\n2月,900,ok\n",
                StandardCharsets.UTF_8);
        var doc = DocumentReader.read(f);
        assertEquals("csv", doc.kind());
        assertTrue(doc.text().contains("month=1月"), doc.text());
        assertTrue(doc.text().contains("amount=1,200"), "引号里的逗号不能被切开: " + doc.text());
        assertTrue(doc.text().contains("note=ok"), doc.text());
    }

    @Test
    @DisplayName("csv 列数不一致要警告——那是「这份数据可能是坏的」")
    void csvWarnsOnRaggedRows() throws Exception {
        Path f = tmp.resolve("ragged.csv");
        Files.writeString(f, "a,b,c\n1,2\n", StandardCharsets.UTF_8);
        var doc = DocumentReader.read(f);
        assertTrue(doc.suspect(), doc.warnings().toString());
        assertTrue(doc.warnings().get(0).contains("列数不一致"), doc.warnings().toString());
    }

    @Test
    @DisplayName("json：带缩进，且坏 JSON 明确报错而不是返回空文本")
    void jsonPrettyOrError() throws Exception {
        Path ok = tmp.resolve("data.json");
        Files.writeString(ok, "{\"a\":{\"b\":1}}", StandardCharsets.UTF_8);
        var doc = DocumentReader.read(ok);
        assertEquals("json", doc.kind());
        assertTrue(doc.text().contains("\"b\""), doc.text());

        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, "{不是 json", StandardCharsets.UTF_8);
        var broken = DocumentReader.read(bad);
        assertTrue(broken.suspect(), broken.warnings().toString());
        assertTrue(broken.warnings().get(0).contains("不是合法的 JSON"));
    }

    @Test
    @DisplayName("不支持的格式明确说不支持，而不是猜")
    void unsupportedIsExplicit() throws Exception {
        Path f = tmp.resolve("old.doc");
        Files.writeString(f, "binary-ish", StandardCharsets.UTF_8);
        var doc = DocumentReader.read(f);
        assertEquals("unsupported", doc.kind());
        assertTrue(doc.warnings().get(0).contains("暂不支持"), doc.warnings().toString());
    }

    // ------------------------------------------------------------------ PDF

    /** 手写一个最小的、未压缩的 PDF（没有外部依赖地造一个 fixture）。 */
    private static byte[] minimalPdf(String content) {
        // 用拼接而不是 String.formatted：PDF 里到处是 %（%PDF-1.4、%%EOF），
        // 拿它当格式串会得到 "UnknownFormatConversion Conversion = 'P'"
        return ("%PDF-1.4\n"
                + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/Contents 4 0 R>>endobj\n"
                + "4 0 obj<</Length " + (content.length() + 40) + ">>stream\n"
                + "BT /F1 12 Tf 72 700 Td (" + content + ") Tj ET\n"
                + "endstream\n"
                + "endobj\n"
                + "trailer<</Root 1 0 R>>\n"
                + "%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("PDF：未压缩的内容流里抠出文字")
    void pdfExtractsText() throws Exception {
        Path f = tmp.resolve("hello.pdf");
        Files.write(f, minimalPdf("Hello PDF 世界"));
        var doc = DocumentReader.read(f);
        assertEquals("pdf", doc.kind());
        assertTrue(doc.text().contains("Hello PDF"), doc.text());
    }

    @Test
    @DisplayName("PDF：FlateDecode 压缩的内容流也要能解开（用 JDK 的 Inflater）")
    void pdfInflatesCompressedStream() throws Exception {
        String content = "BT /F1 12 Tf 72 700 Td (Compressed Text) Tj ET";
        byte[] raw = content.getBytes(StandardCharsets.ISO_8859_1);
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();

        String pdf = "%PDF-1.4\n4 0 obj<</Filter/FlateDecode>>stream\n"
                + new String(out.toByteArray(), StandardCharsets.ISO_8859_1)
                + "\nendstream\nendobj\ntrailer<</Root 1 0 R>>\n%%EOF\n";
        Path f = tmp.resolve("compressed.pdf");
        Files.write(f, pdf.getBytes(StandardCharsets.ISO_8859_1));

        var doc = DocumentReader.read(f);
        assertTrue(doc.text().contains("Compressed Text"), doc.text() + " / " + doc.warnings());
    }

    @Test
    @DisplayName("PDF：抠不出文字时要警告「可能是扫描件」，而不是返回一个空的「成功」")
    void pdfWarnsWhenNoText() throws Exception {
        Path f = tmp.resolve("scan.pdf");
        Files.write(f, "%PDF-1.4\n4 0 obj<</Length 10>>stream\n/Image stuff\nendstream\nendobj\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        var doc = DocumentReader.read(f);
        assertTrue(doc.suspect(), doc.warnings().toString());
        assertTrue(doc.warnings().stream().anyMatch(w -> w.contains("扫描件")),
                doc.warnings().toString());
    }

    // ------------------------------------------------------------------ 工具与围栏

    @Test
    @DisplayName("read_document：读到文档目录里的文件")
    void toolReadsInsideDocsDir() throws Exception {
        Path docs = tmp.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("a.md"), "内容在这里", StandardCharsets.UTF_8);

        var out = DocumentTool.of(docs).handler()
                .handle(new ToolCall("c1", DocumentTool.NAME, "{\"path\":\"a.md\"}"));
        assertTrue(out.ok(), out.content());
        assertTrue(out.content().contains("内容在这里"), out.content());
    }

    @Test
    @DisplayName("路径穿越被挡住：../../etc/passwd 读不成（先 normalize 再比较）")
    void pathTraversalIsBlocked() throws Exception {
        Path docs = tmp.resolve("docs");
        Files.createDirectories(docs);
        Path outside = tmp.getParent() == null ? tmp.resolveSibling("outside.txt")
                : tmp.resolveSibling("outside.txt");
        Files.writeString(outside, "不该被读到", StandardCharsets.UTF_8);

        for (String evil : new String[]{"../../outside.txt", "../outside.txt", "a/../../../outside.txt"}) {
            var out = DocumentTool.of(docs).handler()
                    .handle(new ToolCall("c1", DocumentTool.NAME, "{\"path\":\"" + evil + "\"}"));
            assertFalse(out.ok(), "这条必须被拒绝: " + evil);
            assertEquals("BLOCKED_BY_POLICY", out.errorCode(), out.content());
            assertTrue(out.content().contains("escapes"), "要说清为什么: " + out.content());
        }
    }

    @Test
    @DisplayName("拼错路径时把目录里有什么交给模型，让它自己纠正而不是反复猜")
    void missingFileListsTheDirectory() throws Exception {
        Path docs = tmp.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("real.md"), "x", StandardCharsets.UTF_8);

        var out = DocumentTool.of(docs).handler()
                .handle(new ToolCall("c1", DocumentTool.NAME, "{\"path\":\"rea.md\"}"));
        assertFalse(out.ok());
        assertTrue(out.content().contains("real.md"), out.content());
    }

    @Test
    @DisplayName("没配目录就不是个工具（一个能读文件的工具不该默认就在列表里）")
    void disabledWithoutEnvDir() throws Exception {
        var out = DocumentTool.of(null).handler()
                .handle(new ToolCall("c1", DocumentTool.NAME, "{\"path\":\"a.md\"}"));
        assertFalse(out.ok());
        assertEquals("BLOCKED_BY_POLICY", out.errorCode());
        assertTrue(out.content().contains("APLAT_DOCS_DIR"));
    }

    @Test
    @DisplayName("它不是执行类工具：不声明 commandField，因而策略层不会把它当命令入口")
    void notAnExecutingTool() throws Exception {
        assertFalse(DocumentTool.spec().executesCommands());
        assertEquals("read_document", DocumentTool.NAME);
    }

    @Test
    @DisplayName("结果里带着「摘要 + 警告」，让模型知道这份东西可不可信")
    void resultCarriesSummary() throws Exception {
        Path docs = tmp.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("t.csv"), "a,b\n1\n", StandardCharsets.UTF_8);
        ToolResult out = DocumentTool.of(docs).handler()
                .handle(new ToolCall("c1", DocumentTool.NAME, "{\"path\":\"t.csv\"}"));
        assertTrue(out.ok());
        assertTrue(out.content().contains("csv，"), "要有摘要: " + out.content());
        assertTrue(out.content().contains("⚠"), "有警告就要出现在正文里: " + out.content());
    }
}
