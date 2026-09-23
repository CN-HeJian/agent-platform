package com.aplat.observe;

import com.aplat.durable.TaskRecord;
import com.aplat.durable.TaskState;
import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 指标（U23）：从**事件流与任务表**里聚合，不引入独立的计数器。
 *
 * <h2>为什么不做进程内计数器</h2>
 *
 * <p>进程内计数器（`AtomicLong`）在重启后清零，而且**多实例部署时每个实例只看到自己那一份**。
 * 于是"今天工具调用 1200 次"这种数字会随重启和副本数变化，而它看起来总是个可信的数字——
 * 这比没有指标更糟。
 *
 * <p>从事件流聚合的代价是每次都要扫一遍。在这个规模上完全没问题，而且它换来三件事：
 * 重启后照旧、多实例天然合并、以及**指标和日志说的是同一件事**（都从同一条事件流来）。
 * 真到了要省这点扫描成本的时候，正确做法是把聚合结果也落成表（在同一份事件流上算），
 * 而不是换回进程内计数器。
 *
 * <h2>只统计"事实"，不算"率"</h2>
 *
 * <p>这里只出计数与分位需要的原始量（总数、耗时和）。平均值、P95 那些交给看的人去算——
 * 因为一旦在这里算好，就再也没法从另一个角度看同一批数据了。
 */
public final class Metrics {

    private final Store store;
    private final SessionLog log;

    public Metrics(Store store, SessionLog log) {
        this.store = store;
        this.log = log;
    }

    /** 全量快照。字段名直接就是 Prometheus 指标名，两处不再各起一套名字。 */
    public Map<String, Object> snapshot() {
        long events = 0;
        Map<String, Integer> byType = new TreeMap<>();
        Map<String, Integer> toolCalls = new TreeMap<>();
        Map<String, Integer> toolErrors = new TreeMap<>();
        int replays = 0;
        int resumes = 0;
        int errors = 0;
        long llmChunks = 0;
        long hitlWaitMillis = 0;
        int hitlWaits = 0;
        long toolMillis = 0;
        int toolSpans = 0;

        List<String> sessions = store.sessionIds();
        for (String sessionId : sessions) {
            List<SessionEvent> list = log.events(sessionId);
            events += list.size();

            // 配对用 id：耗时只有配上对才算得出来
            Map<String, SessionEvent> openTools = new LinkedHashMap<>();
            Map<String, SessionEvent> openHitl = new LinkedHashMap<>();

            for (SessionEvent e : list) {
                byType.merge(e.type(), 1, Integer::sum);
                switch (e.type()) {
                    case SessionLog.EV_TOOL_CALL -> {
                        openTools.put(String.valueOf(e.get("id")), e);
                        toolCalls.merge(String.valueOf(e.get("tool")), 1, Integer::sum);
                    }
                    case SessionLog.EV_TOOL_RESULT -> {
                        SessionEvent call = openTools.remove(String.valueOf(e.get("id")));
                        if (call != null) {
                            toolMillis += Duration.between(call.ts(), e.ts()).toMillis();
                            toolSpans++;
                        }
                        if (!"true".equals(e.str("ok"))) {
                            toolErrors.merge(String.valueOf(e.get("tool")), 1, Integer::sum);
                        }
                    }
                    case SessionLog.EV_TOOL_REPLAYED ->
                            replays++;
                    case SessionLog.EV_TURN_RESUMED ->
                            resumes++;
                    case SessionLog.EV_HITL_REQUEST -> {
                        openHitl.put(String.valueOf(e.get("requestId")), e);
                        hitlWaits++;
                    }
                    case SessionLog.EV_HITL_RESOLVED -> {
                        SessionEvent req = openHitl.remove(String.valueOf(e.get("requestId")));
                        if (req != null) {
                            hitlWaitMillis += Duration.between(req.ts(), e.ts()).toMillis();
                        }
                    }
                    case SessionLog.EV_LLM_CHUNK ->
                            llmChunks++;
                    case SessionLog.EV_ERROR ->
                            errors++;
                    default -> {
                    }
                }
            }
        }

        List<TaskRecord> tasks = taskList();
        Map<String, Integer> tasksByState = new TreeMap<>();
        for (TaskRecord t : tasks) {
            tasksByState.merge(t.state().name(), 1, Integer::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aplat_sessions_total", sessions.size());
        out.put("aplat_events_total", events);
        out.put("aplat_llm_chunks_total", llmChunks);
        out.put("aplat_errors_total", errors);
        out.put("aplat_tool_calls_total", sum(toolCalls));
        out.put("aplat_tool_errors_total", sum(toolErrors));
        out.put("aplat_tool_replayed_total", replays);
        out.put("aplat_turns_resumed_total", resumes);
        out.put("aplat_hitl_waits_total", hitlWaits);
        out.put("aplat_hitl_wait_millis_total", hitlWaitMillis);
        out.put("aplat_tool_millis_total", toolMillis);
        out.put("aplat_tool_spans_total", toolSpans);

        out.put("aplat_tasks_total", tasks.size());
        out.put("aplat_tasks_by_state", tasksByState);
        out.put("aplat_tasks_resumable", tasks.stream().filter(t -> t.state().resumable()).count());

        out.put("aplat_tool_calls_by_tool", toolCalls);
        out.put("aplat_tool_errors_by_tool", toolErrors);
        out.put("aplat_events_by_type", byType);
        out.put("generatedAt", Instant.now().toString());
        return out;
    }

    /**
     * Prometheus 文本格式。
     *
     * <p>做它不是为了"看起来专业"：它意味着这套指标**不用改一行代码**就能被现成的
     * 采集器（node_exporter 那一套）抓走。JSON 好看，但没有一个采集器认它。
     */
    public String prometheus() {
        Map<String, Object> snap = snapshot();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : snap.entrySet()) {
            if (e.getKey().startsWith("aplat_") && e.getValue() instanceof Number n) {
                sb.append(e.getKey()).append(' ').append(n).append('\n');
            }
        }
        emitMap(sb, "aplat_tool_calls", snap.get("aplat_tool_calls_by_tool"));
        emitMap(sb, "aplat_tool_errors", snap.get("aplat_tool_errors_by_tool"));
        emitMap(sb, "aplat_events", snap.get("aplat_events_by_type"));

        // 任务状态的枚举要有**零值**：Prometheus 里"这条线消失"和"它是 0"是两件事，
        // 少了零值就画不出阶梯图（图上会突然断一截，看起来像采集器掉线）。
        @SuppressWarnings("unchecked")
        Map<String, Integer> byState = (Map<String, Integer>) snap.get("aplat_tasks_by_state");
        for (TaskState state : TaskState.values()) {
            int value = byState.getOrDefault(state.name(), 0);
            sb.append("aplat_tasks_by_state_by_").append(state.name().toLowerCase())
                    .append(' ').append(value).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void emitMap(StringBuilder sb, String base, Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            sb.append(base).append("_by_").append(sanitize(String.valueOf(e.getKey())))
                    .append(' ').append(e.getValue()).append('\n');
        }
    }

    private List<TaskRecord> taskList() {
        List<TaskRecord> out = new java.util.ArrayList<>();
        store.all("task").values().forEach(json -> TaskRecord.fromJson(json).ifPresent(out::add));
        return out;
    }

    private static int sum(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 标签值只允许 [a-zA-Z0-9_]：Prometheus 的标签里出现别的字符会让整行被丢弃。 */
    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /** 一条事件的类型清单：给运营台做筛选下拉用。 */
    public List<String> eventTypes() {
        TreeSet<String> types = new TreeSet<>();
        for (String sessionId : store.sessionIds()) {
            for (SessionEvent e : log.events(sessionId)) {
                types.add(e.type());
            }
        }
        return List.copyOf(types);
    }
}
