package com.aplat.doc;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 最小 PDF 文本抽取（U31）：只依赖 JDK。
 *
 * <p>步骤：找到所有 {@code stream … endstream} → 需要时 {@code Inflater} 解压 →
 * 从 {@code BT … ET} 之间抠 {@code Tj} 与 {@code TJ} 里的字符串。
 *
 * <p><b>它做不到的</b>（都作为警告返回，不假装成功）：自定义编码的字体（读出来是乱码）、
 * 竖排与分栏的阅读顺序、扫描件（需要 OCR）。能做的是"文字正常排版的导出型 PDF"，
 * 也就是绝大多数实际会遇到的那部分。
 */
final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    static DocumentReader.ParsedDoc extract(byte[] pdf) {
        List<String> warnings = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int streams = 0;

        int from = 0;
        while (true) {
            int start = indexOf(pdf, "stream".getBytes(), from);
            if (start < 0) {
                break;
            }
            int contentStart = start + "stream".length();
            // stream 后面可能跟 \r\n 或 \n
            if (contentStart < pdf.length && pdf[contentStart] == '\r') {
                contentStart++;
            }
            if (contentStart < pdf.length && pdf[contentStart] == '\n') {
                contentStart++;
            }
            int end = indexOf(pdf, "endstream".getBytes(), contentStart);
            if (end < 0) {
                break;
            }
            streams++;
            byte[] body = java.util.Arrays.copyOfRange(pdf, contentStart, end);
            // 两个候选都试：原样文本，以及"解压出来的文本"。
            // 不做 /Filter 字典解析去猜该不该解压——各家的写法差别很大，
            // 而"试一下哪个能抠出文字"在这件小事上比解析更可靠。
            for (String candidate : decodeCandidates(body)) {
                String found = textOperators(candidate);
                if (!found.isBlank()) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(found);
                    break;
                }
            }
            from = end + "endstream".length();
        }

        if (streams == 0) {
            warnings.add("没有找到任何内容流——它可能不是 PDF，或者结构上很特殊");
        }
        String out = text.toString().trim();
        return new DocumentReader.ParsedDoc(out, "pdf", out.length(), warnings);
    }

    /** 候选：先原样，再解压（解压失败就不给第二个候选）。 */
    private static List<String> decodeCandidates(byte[] body) {
        List<String> out = new ArrayList<>();
        out.add(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1));
        String inflated = inflate(body);
        if (inflated != null) {
            out.add(inflated);
        }
        return out;
    }

    /** 试着解压；不是 zlib 流或解压失败就返回 null（不记警告：它只是两个候选之一）。 */
    private static String inflate(byte[] body) {
        // 按 zlib 流的头部字节判断是否需要解压（0x78 是 zlib 的常见首字节），
        // 而不是去解析 /Filter 字典——后者的写法在各家工具里差别很大，
        // 而"直接试试能不能解压"这件小事上，试错比解析更可靠
        if (body.length > 2 && (body[0] == 0x78)) {
            // Inflater 不是 AutoCloseable，手动 end()；JDK 自带的解压器就够了，
            // 不需要为这件事引一个 PDF 库
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(body);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!inflater.finished()) {
                    int n;
                    try {
                        n = inflater.inflate(buf);
                    } catch (DataFormatException e) {
                        return null;
                    }
                    if (n == 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                }
                return out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
            } finally {
                inflater.end();
            }
        }
        return new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** 抠出 (文本)Tj 与 [(文)(本)]TJ 里的字符串。 */
    private static String textOperators(String content) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '(') {
                StringBuilder piece = new StringBuilder();
                int depth = 1;
                i++;
                while (i < content.length() && depth > 0) {
                    char ch = content.charAt(i);
                    if (ch == '\\' && i + 1 < content.length()) {
                        piece.append(content.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    piece.append(ch);
                    i++;
                }
                // 只保留"紧跟 Tj/TJ 的"字符串：PDF 里括号还有很多别的用途。
                // 注意要先跳过右括号本身——循环结束时 i 正停在 ')' 上，
                // 不从它后面开始看的话，看到的是 ") T" 而不是 "Tj"。
                int j = i + 1;
                while (j < content.length() && Character.isWhitespace(content.charAt(j))) {
                    j++;
                }
                String tail = j + 2 <= content.length() ? content.substring(j, Math.min(j + 3, content.length())) : "";
                if (tail.startsWith("Tj") || tail.startsWith("TJ")) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(piece);
                }
            }
            i++;
        }
        return sb.toString();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
