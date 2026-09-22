package com.aplat.session;

import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话事件 → **AG-UI 标准事件**的投影（U08 的实体）。
 *
 * <p>这和 {@link AgUiMapper} 的区别很重要，别混：
 * <ul>
 *   <li>{@code AgUiMapper} 只是给事件换个名字，**保留我们自己的信封**
 *       （{@code {type, sessionId, seq, payload}}）——给内置控制台和 curl 看的方言。</li>
 *   <li>这个类是<b>真正的 AG-UI 协议投影</b>：产出的对象是规范里的字段
 *       （{@code threadId} / {@code messageId} / {@code delta} / {@code toolCallId} …），
 *       能被 {@code @ag-ui/client} 的 {@code HttpAgent}、CopilotKit 直接消费。</li>
 * </ul>
 *
 * <p>它是<b>有状态的流式投影器</b>，因为协议本身有状态：文本必须被
 * {@code TEXT_MESSAGE_START} … {@code TEXT_MESSAGE_END} 包起来，工具调用必须
 * {@code TOOL_CALL_START} → {@code ARGS} → {@code END}。而我们的内部事件流是"一行一条"的，
 * 所以这里负责把"扁平的记账事件"翻译成"配对的结构化事件"。
 *
 * <p>三条映射上的取舍：
 * <ol>
 *   <li><b>一条内部事件可能产出 0..n 条 AG-UI 事件</b>（比如第一个 text chunk 要同时补
 *       START）。所以返回的是列表，不是单条。</li>
 *   <li><b>内部记账事件不外泄</b>：{@code input.claimed}、{@code context.prepared} 是
 *       给回放排查用的，前端不需要看见——只有当某个事件确实无法归类时才走
 *       {@code CUSTOM}（协议允许，前端可安全忽略）。</li>
 *   <li><b>工具结果的内容 = 模型看到的那段文字</b>（含 {@code ERROR[code]} 前缀）。
 *       UI 上看到的和模型看到的一致，排查时不用两头对。</li>
 * </ol>
 *
 * <p>非线程安全：一次 run 一个实例，由 SSE 处理线程独占。
 */
public final class AgUiProjector {

    private final String threadId;
    private final String runId;

    private final Deque<String> openSteps = new ArrayDeque<>();
    private String openMessageId;
    private boolean runStarted;

    public AgUiProjector(String threadId, String runId) {
        this.threadId = threadId;
        this.runId = runId;
    }

    /**
     * 把一条内部事件投影成 0..n 条 AG-UI 事件。
     *
     * @return 顺序敏感的 AG-UI 事件列表（每条至少含 {@code type}）
     */
    public List<Map<String, Object>> project(SessionEvent event) {
        List<Map<String, Object>> out = new ArrayList<>();
        String payloadText = event.str("text");

        switch (event.type()) {
            case SessionLog.EV_TURN_START -> {
                runStarted = true;
                out.add(ev("RUN_STARTED", "threadId", threadId, "runId", runId));
            }
            case SessionLog.EV_STEP_START -> {
                // 上一个 step 到此结束：先收消息、再收 step，最后才开新的
                closeMessage(out);
                closeSteps(out);
                String stepName = "step-" + event.get("step");
                openSteps.addLast(stepName);
                out.add(ev("STEP_STARTED", "stepName", stepName));
            }
            case SessionLog.EV_LLM_CHUNK -> {
                if (payloadText == null) {
                    break; // usage 之类的记账 chunk，不外泄
                }
                if (openMessageId == null) {
                    openMessageId = "msg-" + event.seq();
                    out.add(ev("TEXT_MESSAGE_START", "messageId", openMessageId, "role", "assistant"));
                }
                out.add(ev("TEXT_MESSAGE_CONTENT", "messageId", openMessageId, "delta", payloadText));
            }
            case SessionLog.EV_TOOL_CALL -> {
                // 助手先说话、再发起调用：先把文本消息收口，工具调用才是独立的一段
                closeMessage(out);
                String toolCallId = String.valueOf(event.get("id"));
                out.add(ev("TOOL_CALL_START",
                        "toolCallId", toolCallId,
                        "toolCallName", String.valueOf(event.get("tool"))));
                String args = String.valueOf(event.get("args"));
                out.add(ev("TOOL_CALL_ARGS", "toolCallId", toolCallId, "delta", args == null ? "{}" : args));
            }
            case SessionLog.EV_TOOL_RESULT -> {
                String toolCallId = String.valueOf(event.get("id"));
                out.add(ev("TOOL_CALL_END", "toolCallId", toolCallId));
                out.add(ev("TOOL_CALL_RESULT",
                        "messageId", "toolmsg-" + event.seq(),
                        "toolCallId", toolCallId,
                        "role", "tool",
                        "content", observationOf(event)));
            }
            case SessionLog.EV_STATE_SNAPSHOT -> out.add(ev("STATE_SNAPSHOT",
                    "snapshot", Map.of(
                            "step", String.valueOf(event.get("step")),
                            "messages", String.valueOf(event.get("messages")))));
            case SessionLog.EV_TURN_CLOSED -> {
                closeMessage(out);
                closeSteps(out);
                if (runStarted) {
                    out.add(ev("RUN_FINISHED", "threadId", threadId, "runId", runId));
                    runStarted = false;
                }
            }
            case SessionLog.EV_ERROR -> {
                closeMessage(out);
                closeSteps(out);
                out.add(ev("RUN_ERROR",
                        "message", String.valueOf(event.get("error")),
                        "code", "AGENT_LOOP_ERROR"));
                runStarted = false;
            }
            default -> {
                // input.claimed / context.prepared 等内部记账：不外泄
            }
        }
        return out;
    }

    /**
     * run 结束后的收尾：把仍然打开的消息与 step 关掉。
     *
     * <p>必须有这一步——否则一旦 turn 在文本中途异常结束，前端会永远等
     * {@code TEXT_MESSAGE_END}，表现为"光标一直闪"。
     */
    public List<Map<String, Object>> finish() {
        List<Map<String, Object>> out = new ArrayList<>();
        closeMessage(out);
        closeSteps(out);
        if (runStarted) {
            out.add(ev("RUN_FINISHED", "threadId", threadId, "runId", runId));
            runStarted = false;
        }
        return out;
    }

    public String threadId() {
        return threadId;
    }

    public String runId() {
        return runId;
    }

    private void closeMessage(List<Map<String, Object>> out) {
        if (openMessageId != null) {
            out.add(ev("TEXT_MESSAGE_END", "messageId", openMessageId));
            openMessageId = null;
        }
    }

    /**
     * 关掉所有仍然打开的 step。
     *
     * <p><b>这不是可选的。</b>AG-UI 客户端会校验生命周期：
     * 只要有 {@code STEP_STARTED} 没有配对的 {@code STEP_FINISHED}，
     * 收到 {@code RUN_FINISHED} 时会直接拒绝整条流并报
     * {@code Cannot send 'RUN_FINISHED' while steps are still active}。
     * 这个 bug 靠单测看不出来（要真的拿客户端跑一次才会暴露），所以这里
     * 补了成对性的回归用例。
     */
    private void closeSteps(List<Map<String, Object>> out) {
        while (!openSteps.isEmpty()) {
            out.add(ev("STEP_FINISHED", "stepName", openSteps.pollLast()));
        }
    }

    /** 与 AgentLoop 回填给模型的观察保持完全一致（含 ERROR[code] 前缀）。 */
    private static String observationOf(SessionEvent event) {
        Object ok = event.get("ok");
        String content = String.valueOf(event.get("content"));
        if (Boolean.TRUE.equals(ok)) {
            return content;
        }
        return "ERROR[" + event.get("errorCode") + "] " + content;
    }

    /** 按"type 在最前"的顺序构造，方便人肉看 JSON。 */
    private static Map<String, Object> ev(String type, Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }
}
