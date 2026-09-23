package com.aplat.observe;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从会话事件流**投影**出 span 树（U23）。
 *
 * <h2>为什么是"投影"而不是"到处埋点"</h2>
 *
 * <p>另一种做法是在执行路径上埋 {@code span.start()/end()}。那样有两个代价，第二个是致命的：
 *
 * <ol>
 *   <li>要改每一处执行路径；</li>
 *   <li><b>崩溃的会话会丢掉最后一个 span</b>——进程都没了，谁来调 {@code end()}？
 *       而"崩掉的那一次"恰恰是最需要看追踪的那一次。</li>
 * </ol>
 *
 * <p>事件流里已经有全部事实，投影出来就跑得通。于是：
 * <ul>
 *   <li>**崩溃的会话也有完整 trace**，且"最后那个没有结束事件的 span"就是"崩在哪一步"的答案；</li>
 *   <li>续跑与幂等回放天然落在同一棵树里（回放是带 {@code replayed=true} 的工具 span）；</li>
 *   <li>历史会话不需要"当时开了追踪"这个前提，随时可以补看。</li>
 * </ul>
 *
 * <h2>配对靠 id，不靠顺序</h2>
 *
 * <p>{@code tool.call} 与 {@code tool.result} 靠 call id 配，{@code hitl.requested} 与
 * {@code hitl.resolved} 靠 requestId 配。**不能按"下一个同类事件"配**：一个 step 里的两次
 * 同名工具调用、以及并发的人机确认，都会配错——而配错的表现是"耗时看起来很正常"，
 * 于是没人会去查。
 *
 * <p>没配上对的事件**不丢**，留成一个开口的 span（{@code ended=false}），
 * 并计入 {@link Trace#openSpans()}。那正是"崩在这中间"的证据，比一棵干净但假的树有价值。
 */
public final class TraceBuilder {

    private TraceBuilder() {
    }

    /** 一个 span。{@code ended=false} = 事件流里没有它的结束事件（崩了，或还在跑）。 */
    public record Span(
            String name,
            String kind,
            Instant start,
            Instant end,
            Map<String, Object> attributes,
            List<Span> children,
            boolean ended) {

        public Span {
            attributes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            children = List.copyOf(children);
        }

        public long durationMillis() {
            return end == null ? 0 : Duration.between(start, end).toMillis();
        }
    }

    /** 投影结果。 */
    public record Trace(String sessionId, List<Span> roots, List<SessionEvent> events, int openSpans) {

        /** 把树摊平：前端画时间线、以及断言"有几个工具 span"都更方便。 */
        public List<Span> flatten() {
            List<Span> out = new ArrayList<>();
            Deque<Span> stack = new ArrayDeque<>(roots);
            while (!stack.isEmpty()) {
                Span s = stack.pollFirst();
                out.add(s);
                stack.addAll(s.children());
            }
            return out;
        }

        public List<Span> byKind(String kind) {
            List<Span> out = new ArrayList<>();
            for (Span s : flatten()) {
                if (s.kind().equals(kind)) {
                    out.add(s);
                }
            }
            return out;
        }

        /** 整条会话从第一个事件到最后一个事件的跨度（不是各 span 之和——那些是嵌套的）。 */
        public long totalMillis() {
            if (events.isEmpty()) {
                return 0;
            }
            return Duration.between(events.get(0).ts(), events.get(events.size() - 1).ts()).toMillis();
        }

        public Map<String, Object> summary() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessionId", sessionId);
            out.put("events", events.size());
            out.put("totalMillis", totalMillis());
            out.put("spans", flatten().size());
            out.put("openSpans", openSpans);
            out.put("turns", byKind("turn").size());
            out.put("steps", byKind("step").size());
            out.put("toolCalls", byKind("tool").size());
            out.put("hitlWaits", byKind("hitl").size());
            return out;
        }
    }

    // --------------------------------------------------------------- 投影主体

    public static Trace build(SessionLog log, String sessionId) {
        return build(sessionId, log.events(sessionId));
    }

    /**
     * 把一个会话的事件流投影成 span 树。
     *
     * <p>内部用可变节点搭树，最后一次性冻结成不可变的 {@link Span}——
     * 中间过程不该假装不可变（那只会逼出一堆绕路的写法）。
     */
    public static Trace build(String sessionId, List<SessionEvent> events) {
        List<Node> roots = new ArrayList<>();
        Node turn = null;
        Node step = null;
        final Map<String, Node> tools = new LinkedHashMap<>();
        final Map<String, Node> hitls = new LinkedHashMap<>();

        for (SessionEvent e : events) {
            switch (e.type()) {
                case SessionLog.EV_TURN_START, SessionLog.EV_TURN_RESUMED -> {
                    turn = new Node("turn", "turn", e.ts());
                    boolean resumed = e.type().equals(SessionLog.EV_TURN_RESUMED);
                    turn.attr("resumed", resumed);
                    if (resumed) {
                        turn.attr("fromStep", e.get("fromStep"));
                        turn.attr("restoredMessages", e.get("restoredMessages"));
                    }
                    step = null;
                    roots.add(turn);
                }
                case SessionLog.EV_STEP_START -> {
                    // 上一步仍然展开着：它已经结束了（不然不会开始新的一步）。
                    // 同样是推断——事件流里没有 "step.ended"。标出来。
                    if (step != null && !step.closed) {
                        step.attr("endInferred", true);
                        step.close(e.ts());
                    }
                    step = new Node("step " + e.get("step"), "step", e.ts());
                    step.attr("step", e.get("step"));
                    // step 挂到 turn 下；没有 turn 就直接进根（比如从事件流中段开始投影）
                    if (turn != null) {
                        turn.children.add(step);
                    } else {
                        roots.add(step);
                    }
                }
                case SessionLog.EV_TOOL_CALL -> {
                    Node n = new Node("tool " + e.get("tool"), "tool", e.ts());
                    n.attr("tool", e.get("tool"));
                    n.attr("callId", e.get("id"));
                    n.attr("args", e.get("args"));
                    tools.put(String.valueOf(e.get("id")), n);
                    attach(roots, turn, step, n);
                }
                case SessionLog.EV_TOOL_RESULT -> {
                    Node n = tools.remove(String.valueOf(e.get("id")));
                    if (n == null) {
                        // 结果没有对应的调用事件：续跑回放时会这样（结果来自库、调用来自上一个进程）。
                        // 不丢——它是一条真实发生过的事实。
                        Node orphan = new Node("tool-result(orphan)", "tool", e.ts());
                        orphan.attr("tool", e.get("tool"));
                        orphan.attr("orphanResult", true);
                        orphan.close(e.ts());
                        roots.add(orphan);
                        break;
                    }
                    String content = e.str("content");
                    n.attr("ok", e.get("ok"));
                    n.attr("errorCode", e.get("errorCode"));
                    n.attr("resultChars", content == null ? 0 : content.length());
                    n.close(e.ts());
                }
                case SessionLog.EV_TOOL_REPLAYED -> {
                    Node n = new Node("tool " + e.get("tool") + "（回放，未执行）", "tool", e.ts());
                    n.attr("tool", e.get("tool"));
                    n.attr("callId", e.get("id"));
                    n.attr("replayed", true);
                    n.attr("reason", e.get("reason"));
                    n.close(e.ts());
                    attach(roots, turn, step, n);
                }
                case SessionLog.EV_HITL_REQUEST -> {
                    Node n = new Node("等待人工确认", "hitl", e.ts());
                    n.attr("tool", e.get("tool"));
                    n.attr("reason", e.get("reason"));
                    n.attr("timeoutSec", e.get("timeoutSec"));
                    hitls.put(String.valueOf(e.get("requestId")), n);
                    attach(roots, turn, step, n);
                }
                case SessionLog.EV_HITL_RESOLVED -> {
                    Node n = hitls.remove(String.valueOf(e.get("requestId")));
                    if (n != null) {
                        // 人机往返的耗时必须是 span 的一部分：那 3 秒是人在点按钮，不是系统慢
                        n.attr("decision", e.get("decision"));
                        n.attr("note", String.valueOf(e.get("note")));
                        n.close(e.ts());
                    }
                }
                case SessionLog.EV_HITL_PREAPPROVED, SessionLog.EV_HITL_AUTO -> {
                    Node n = new Node("无需确认（" + String.valueOf(e.get("approvedBy")) + "）",
                            "hitl", e.ts());
                    n.attr("tool", e.get("tool"));
                    n.attr("approvedBy", e.get("approvedBy"));
                    n.attr("autoApproved", true);
                    n.close(e.ts());
                    attach(roots, turn, step, n);
                }
                case SessionLog.EV_TURN_CLOSED -> {
                    if (turn == null) {
                        Node orphan = new Node("turn(只有收口事件)", "turn", e.ts());
                        orphan.attr("reason", String.valueOf(e.get("reason")));
                        orphan.close(e.ts());
                        roots.add(orphan);
                        break;
                    }
                    // 收口 turn 时**顺带收口当前 step**：事件流里没有 "step.ended" 这个事件
                    // （循环是在 step 末尾 break 的），所以这一步是**推断**，不是事实。
                    // 推断在这里是安全的：turn.closed 只在"本步的活干完了"之后才写，
                    // 因此不可能出现"turn 已收口、step 还在跑"。
                    // 推断出来的结束时间要标出来——审计里"事实"与"推断"必须能区分。
                    if (step != null && !step.closed) {
                        step.attr("endInferred", true);
                        step.close(e.ts());
                    }
                    turn.attr("reason", String.valueOf(e.get("reason")));
                    turn.close(e.ts());
                }
                default -> {
                    // 其它事件不单独成 span：它们是"某个 step 上的读数"，记成计数更合适
                    if (step != null) {
                        String key = e.type() + "_count";
                        step.attr(key, ((Number) step.attrs.getOrDefault(key, 0)).intValue() + 1);
                    }
                }
            }
        }

        // 收尾：把没有结束事件的 span 留着（不补一个假的结束时间）
        int open = 0;
        for (Node n : all(roots)) {
            if (!n.closed) {
                n.attr("note", "没有等到结束事件：崩在这中间，或者还在跑");
                open++;
            }
        }

        List<Span> frozen = new ArrayList<>();
        for (Node n : roots) {
            frozen.add(n.freeze());
        }
        return new Trace(sessionId, List.copyOf(frozen), List.copyOf(events), open);
    }

    private static List<Node> all(List<Node> roots) {
        List<Node> out = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            Node n = stack.pollFirst();
            out.add(n);
            stack.addAll(n.children);
        }
        return out;
    }

    /** 挂到最内层的容器下：step 优先，其次 turn，都没有就挂到根。 */
    private static void attach(List<Node> roots, Node turn, Node step, Node child) {
        if (step != null) {
            step.children.add(child);
        } else if (turn != null) {
            turn.children.add(child);
        } else {
            roots.add(child);
        }
    }

    /** 内部可变节点。只在投影过程中存在，最后冻结。 */
    private static final class Node {
        private final String name;
        private final String kind;
        private final Instant start;
        private final Map<String, Object> attrs = new LinkedHashMap<>();
        private final List<Node> children = new ArrayList<>();
        private Instant end;
        private boolean closed;

        private Node(String name, String kind, Instant start) {
            this.name = name;
            this.kind = kind;
            this.start = start;
        }

        private void attr(String key, Object value) {
            attrs.put(key, value);
        }

        private void close(Instant at) {
            this.end = at;
            this.closed = true;
        }

        private Span freeze() {
            List<Span> kids = new ArrayList<>();
            for (Node c : children) {
                kids.add(c.freeze());
            }
            return new Span(name, kind, start, end, attrs, kids, closed);
        }
    }
}
