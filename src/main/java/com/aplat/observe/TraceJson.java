package com.aplat.observe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link TraceBuilder.Trace} 变成可以直接序列化的 Map（U23）。
 *
 * <p>为什么手写这一层而不用 {@code MAPPER.valueToTree(trace)}：{@code Span} 里有
 * {@link java.time.Instant}，而 Jackson 默认不认它（要额外注册 jsr310 模块）。
 * 与其为了省这 20 行去引一个模块、再在别处踩"为什么时间序列化成了数组"的坑，
 * 不如明确地把要暴露的字段写下来——**顺便也就定义了对外契约**：
 * 前端能拿到哪些字段，这里就是唯一的地方。
 */
public final class TraceJson {

    private TraceJson() {
    }

    public static Map<String, Object> view(TraceBuilder.Trace trace) {
        Map<String, Object> out = new LinkedHashMap<>(trace.summary());
        List<Map<String, Object>> spans = new ArrayList<>();
        for (TraceBuilder.Span s : trace.roots()) {
            spans.add(span(s));
        }
        out.put("roots", spans);
        return out;
    }

    private static Map<String, Object> span(TraceBuilder.Span s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", s.name());
        out.put("kind", s.kind());
        out.put("start", s.start().toString());
        out.put("end", s.end() == null ? null : s.end().toString());
        out.put("durationMillis", s.durationMillis());
        // ended=false 是这张图里最有价值的一个字段：它说明"这一格没有结束事件"
        out.put("ended", s.ended());
        out.put("attributes", s.attributes());
        if (!s.children().isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (TraceBuilder.Span c : s.children()) {
                kids.add(span(c));
            }
            out.put("children", kids);
        }
        return out;
    }
}
