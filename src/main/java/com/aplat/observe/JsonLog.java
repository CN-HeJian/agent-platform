package com.aplat.observe;

import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化日志（U23）：一行一条 JSON。
 *
 * <h2>为什么服务日志要变成 JSON</h2>
 *
 * <p>给人看的日志和给机器看的日志，需求是冲突的：前者要句子，后者要字段。
 * 折中方案（"半结构化"：{@code 2026-09-23 10:00 [WARN] 拒绝了请求 sessionId=s1 code=BLOCKED}）
 * 两边都不好用——人读着累，机器还得写正则去抠。
 *
 * <p>所以这里选边：**服务日志（不是会话事件）一律 JSON Lines**。
 * 人要看就 {@code | jq}，机器要看直接吃。会话事件另有一套（append-only 的事件流，
 * 那是业务真相），两者不混——把服务日志塞进会话事件会让"回放一次会话"带上无穷多的噪声。
 *
 * <h2>刻意不做的事</h2>
 *
 * <p>不引日志框架（logback/slf4j）。这一版需要的能力只有"带字段的一行 JSON"，
 * 而日志框架带来的配置面（appender、encoder、异步队列、MDC）一个都不需要。
 * 真需要异步与轮转时再换——那时是运维需求，不是功能需求。
 */
public final class JsonLog {

    private final String logger;
    private final PrintStream out;
    private final boolean enabled;

    private JsonLog(String logger, PrintStream out, boolean enabled) {
        this.logger = logger;
        this.out = out;
        this.enabled = enabled;
    }

    public static JsonLog of(String logger) {
        return new JsonLog(logger, System.out, true);
    }

    /** 关掉：测试里不需要服务日志刷屏。 */
    public static JsonLog silent(String logger) {
        return new JsonLog(logger, System.out, false);
    }

    public void info(String msg, Map<String, Object> fields) {
        write("INFO", msg, fields, null);
    }

    public void warn(String msg, Map<String, Object> fields) {
        write("WARN", msg, fields, null);
    }

    public void error(String msg, Throwable cause, Map<String, Object> fields) {
        write("ERROR", msg, fields, cause);
    }

    private void write(String level, String msg, Map<String, Object> fields, Throwable cause) {
        if (!enabled) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("level", level);
        line.put("logger", logger);
        line.put("msg", msg);
        if (fields != null) {
            line.putAll(fields);
        }
        if (cause != null) {
            // 异常只记类型与消息，不记堆栈：堆栈让一条日志涨到几十行，
            // 而"哪个类、什么原因"在 95% 的排查里就够了。要堆栈请复现，别指望日志。
            line.put("error", cause.getClass().getName());
            line.put("cause", String.valueOf(cause.getMessage()));
        }
        // 逐字段自己转义，而不是引一个 JSON 库：这里只有一个"对象转字符串"的动作，
        // 而引库意味着这条 8 行的方法要依赖一个全功能的序列化器
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : line.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        // 一条日志必须原子地落：多线程下拼一半被另一个线程插进去，那条 JSON 就废了
        synchronized (out) {
            out.println(sb);
            out.flush();
        }
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // 控制字符一律转义：它们会让"一行一条"这个前提不成立
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
