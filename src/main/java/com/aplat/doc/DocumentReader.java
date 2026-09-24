package com.aplat.doc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析（U31）：把几种常见格式读成**给模型看的文本**。
 *
 * <h2>不做全格式支持，以及为什么</h2>
 *
 * <p>真实世界里"文档"有几十种格式，其中 docx/xlsx/pptx 都是带 XML 的 zip、
 * PDF 是一整套排版语言。全支持意味着引一个重型库（PDFBox / POI），
 * 而它带来的依赖面和维护成本远超这一版需要的东西。
 *
 * <p>所以这里支持四类**零依赖**能做的：纯文本 / Markdown、CSV、JSON、PDF（最小实现）。
 * 其余格式明确报"不支持"，而不是猜——**猜出来的文本会带着错误，而错误不会报错**。
 *
 * <h2>PDF 是最小实现，这一点要说清</h2>
 *
 * <p>做法：找出所有 {@code stream … endstream}，需要时按 {@code /FlateDecode} 用
 * JDK 自带的 {@code Inflater} 解压，然后从 {@code BT … ET} 里抠 {@code Tj} / {@code TJ} 的文本。
 * 它能处理"文字是正常排版的"PDF（也就是绝大多数导出型 PDF）。
 *
 * <p>它**做不到的**（都写在 {@link ParsedDoc#warnings()} 里，不假装成功）：
 * 字体用了自定义编码（读出来是乱码）、竖排、分栏的阅读顺序、扫描件（那需要 OCR）。
 * 遇到这类文件时它会给出"可能不完整"的警告——**一个知道自己不确定的解析器，
 * 比一个自信地给出乱文本的有用得多**。
 */
public final class DocumentReader {

    /** 解析结果。{@code warnings} 不是日志：它要跟着文本一起交给模型与调用方。 */
    public record ParsedDoc(String text, String kind, int charCount, List<String> warnings) {

        public boolean suspect() {
            return !warnings.isEmpty();
        }

        public String summary() {
            return kind + "，" + charCount + " 字符"
                    + (warnings.isEmpty() ? "" : "，⚠ " + warnings.size() + " 条警告");
        }
    }

    /** 拒绝解析的原因（不是异常：它要出现在结果里）。 */
    public static final String UNSUPPORTED = "暂不支持这个格式（这一版只做 txt/md、csv、json、pdf）";

    private DocumentReader() {
    }

    public static ParsedDoc read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        List<String> warnings = new ArrayList<>();

        if (name.endsWith(".pdf")) {
            ParsedDoc pdf = PdfTextExtractor.extract(Files.readAllBytes(file));
            List<String> all = new ArrayList<>(pdf.warnings());
            if (pdf.text().isBlank()) {
                all.add("这份 PDF 里没有抠出文字：它可能是扫描件（需要 OCR）、"
                        + "字体用了自定义编码、或者文字是画出来的");
            }
            all.addAll(warnings);
            // 注意这里要**重新构造**：extractor 不知道"空白文本"这件事意味着什么，
            // 只有这一层知道——警告必须合并后返回，而不是各自返回一部分。
            return new ParsedDoc(pdf.text(), pdf.kind(), pdf.charCount(), List.copyOf(all));
        }
        if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            return csv(Files.readString(file, StandardCharsets.UTF_8), name.endsWith(".tsv"), warnings);
        }
        if (name.endsWith(".json")) {
            return json(Files.readString(file, StandardCharsets.UTF_8), warnings);
        }
        if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")
                || name.endsWith(".log")) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return new ParsedDoc(text, "text", text.length(), warnings);
        }
        return new ParsedDoc("", "unsupported", 0, List.of(UNSUPPORTED + "：" + name));
    }

    /**
     * CSV → 每行一条"字段=值"的文本。
     *
     * <p>不直接把表格原样丢给模型：{@code a,b,c} 这种裸文本在第 8 列之后就分不清谁是谁了，
     * 而带上表头名之后，模型不需要去数列数。（引号里含分隔符的情况也处理了。）
     */
    private static ParsedDoc csv(String raw, boolean tsv, List<String> warnings) {
        char sep = tsv ? '\t' : ',';
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return new ParsedDoc("", "csv", 0, List.of("空文件"));
        }
        List<String> header = split(lines.get(0), sep);
        StringBuilder sb = new StringBuilder();
        sb.append("表头（").append(header.size()).append(" 列）：")
                .append(String.join(" | ", header)).append('\n');
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = split(lines.get(i), sep);
            sb.append("行 ").append(i).append(": ");
            for (int c = 0; c < cells.size(); c++) {
                String key = c < header.size() ? header.get(c) : "列" + (c + 1);
                sb.append(key).append('=').append(cells.get(c));
                if (c < cells.size() - 1) {
                    sb.append("; ");
                }
            }
            if (cells.size() != header.size()) {
                warnings.add("行 " + i + " 有 " + cells.size() + " 列，表头有 " + header.size()
                        + " 列——列数不一致，这份 CSV 可能是坏的");
            }
            sb.append('\n');
        }
        String text = sb.toString();
        return new ParsedDoc(text, "csv", text.length(), warnings);
    }

    /** 按分隔符切，支持双引号包裹（含转义的双写引号）。 */
    private static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == sep && !inQuotes) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** JSON → 带缩进的文本，让模型能看出层级。 */
    private static ParsedDoc json(String raw, List<String> warnings) {
        try {
            Object tree = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Object.class);
            String pretty = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(tree);
            if (pretty.length() > 40_000) {
                pretty = pretty.substring(0, 40_000);
                warnings.add("这份 JSON 超过 4 万字符，已截断——请先让模型按字段挑，而不是整个吞下去");
            }
            return new ParsedDoc(pretty, "json", pretty.length(), warnings);
        } catch (Exception e) {
            return new ParsedDoc("", "json", 0,
                    List.of("不是合法的 JSON：" + e.getMessage()));
        }
    }
}
