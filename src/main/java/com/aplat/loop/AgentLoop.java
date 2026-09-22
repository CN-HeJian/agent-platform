package com.aplat.loop;

import com.aplat.kernel.EventBus;
import com.aplat.seam.ContextProvider;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.LlmChunk;
import com.aplat.seam.LlmMessage;
import com.aplat.seam.LlmRequest;
import com.aplat.seam.PreparedContext;
import com.aplat.seam.SessionEvent;
import com.aplat.seam.SessionLog;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.tools.Json;
import com.aplat.tools.ToolPipeline;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 循环：一个 turn = 若干 step，每个 step 是一次"思考→行动→观察"。
 *
 * <p>全部依赖构造注入，没有服务定位、没有反射、没有插件壳——换任何一个实现都是改一行装配。
 *
 * <p>三条不可动摇的规则：
 * <ol>
 *   <li>进上下文的每一样东西先落事件（"模型可见的必已记录"）；</li>
 *   <li>任何一步失败都不炸循环，而是转成观察回填，让模型自己换路；</li>
 *   <li>终止条件只有三个：模型收口、步数用尽、硬错误——绝不无限重试。</li>
 * </ol>
 */
public final class AgentLoop {

    private final LlmAdapter llm;
    private final ToolPipeline pipeline;
    private final ContextProvider context;
    private final SessionLog log;
    private final EventBus bus;
    private final LoopBudget budget;
    private final String systemPrompt;

    public AgentLoop(LlmAdapter llm,
                     ToolPipeline pipeline,
                     ContextProvider context,
                     SessionLog log,
                     EventBus bus,
                     LoopBudget budget,
                     String systemPrompt) {
        this.llm = llm;
        this.pipeline = pipeline;
        this.context = context;
        this.log = log;
        this.bus = bus;
        this.budget = budget;
        this.systemPrompt = systemPrompt;
    }

    public TurnResult run(String sessionId, String userInput) {
        return run(sessionId, List.of(), userInput);
    }

    /**
     * 带历史的 run：多轮对话时把之前的轮次作为上下文喂进去。
     *
     * <p>没有这个重载，每次 run 都从 {@code [system, user]} 开始，模型完全不知道
     * 上一句说了什么——前端看起来就是"它失忆了"。
     *
     * <p>谁是历史的持有者？**AG-UI 里是客户端**（每次 run 把完整 messages 发过来），
     * 我们照此照办，而不是从会话日志里回放——否则同一段历史会被推给前端两次。
     *
     * @param history 之前的 user/assistant 轮次；不含本轮输入，也不含 system
     */
    public TurnResult run(String sessionId, List<LlmMessage> history, String userInput) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        messages.addAll(history);
        messages.add(LlmMessage.user(userInput));

        log.append(sessionId, SessionLog.EV_INPUT,
                Map.of("text", userInput, "historyTurns", history.size()));
        log.append(sessionId, SessionLog.EV_TURN_START, Map.of("input", userInput));

        String finalText = null;
        TurnResult.Status status = TurnResult.Status.MAX_STEPS;
        int stepsUsed = 0;

        for (int step = 1; step <= budget.maxSteps(); step++) {
            // 循环变量不能进 lambda，这里取一份有效最终变量给流式回调用
            final int stepNo = step;
            stepsUsed = stepNo;
            // 1) 准备上下文：压缩决策必须留痕
            PreparedContext prepared = context.prepare(messages, budget.contextBudgetTokens());
            Map<String, Object> ctxPayload = new LinkedHashMap<>();
            ctxPayload.put("step", step);
            ctxPayload.put("estimatedTokens", prepared.estimatedTokens());
            ctxPayload.put("compressed", prepared.compressed());
            ctxPayload.put("dropped", prepared.dropped().size());
            log.append(sessionId, SessionLog.EV_CONTEXT_PREPARED, ctxPayload);

            log.append(sessionId, SessionLog.EV_STEP_START, Map.of("step", step));

            // 2) 模型流式输出
            LlmRequest request = LlmRequest.of(systemPrompt, prepared.messages(), pipeline.specsView());
            List<ToolCall> toolCalls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            String[] finish = {null};

            try {
                llm.stream(request, chunk -> {
                    switch (chunk) {
                        case LlmChunk.TextDelta d -> {
                            if (!d.text().isEmpty()) {
                                text.append(d.text());
                                log.append(sessionId, SessionLog.EV_LLM_CHUNK,
                                        Map.of("step", stepNo, "text", d.text()));
                            }
                        }
                        case LlmChunk.ToolCallDelta d -> toolCalls.add(d.call());
                        case LlmChunk.Done d -> finish[0] = d.finishReason();
                        case LlmChunk.Usage u -> log.append(sessionId, SessionLog.EV_LLM_CHUNK,
                                Map.of("step", stepNo, "usage", u.promptTokens() + "/" + u.completionTokens()));
                    }
                });
            } catch (Exception e) {
                log.append(sessionId, SessionLog.EV_ERROR,
                        Map.of("step", step, "error", String.valueOf(e.getMessage())));
                status = TurnResult.Status.ERROR;
                break;
            }

            // 3) 没有工具调用 → 收口
            if (toolCalls.isEmpty()) {
                finalText = text.toString();
                status = TurnResult.Status.COMPLETED;
                log.append(sessionId, SessionLog.EV_TURN_CLOSED,
                        Map.of("step", step, "reason", "completed", "finishReason",
                                String.valueOf(finish[0]), "text", finalText));
                break;
            }

            // 4) 有工具调用 → 记下 assistant 意图，逐个执行，把结果作为观察回填
            messages.add(LlmMessage.assistantToolCalls(text.toString(), toolCalls));
            for (ToolCall call : toolCalls) {
                log.append(sessionId, SessionLog.EV_TOOL_CALL, Map.of(
                        "step", step, "id", String.valueOf(call.id()),
                        "tool", String.valueOf(call.name()),
                        "args", String.valueOf(call.argumentsJson())));

                ToolResult result = pipeline.execute(sessionId, call);

                Map<String, Object> resPayload = new LinkedHashMap<>();
                resPayload.put("step", step);
                resPayload.put("id", call.id());
                resPayload.put("tool", call.name());
                resPayload.put("ok", result.ok());
                resPayload.put("errorCode", result.errorCode());
                resPayload.put("content", result.content());
                log.append(sessionId, SessionLog.EV_TOOL_RESULT, resPayload);

                // 失败也照样回填——这正是模型自纠的输入
                String observation = result.ok()
                        ? result.content()
                        : "ERROR[" + result.errorCode() + "] " + result.content();
                messages.add(LlmMessage.tool(call.id(), observation));
            }

            // 5) 状态快照：便于崩溃后从最近一步续跑
            log.append(sessionId, SessionLog.EV_STATE_SNAPSHOT, Map.of(
                    "step", step, "messages", messages.size(), "state", Json.write(Map.of("step", step))));
        }

        if (status == TurnResult.Status.MAX_STEPS && finalText == null) {
            log.append(sessionId, SessionLog.EV_TURN_CLOSED,
                    Map.of("step", stepsUsed, "reason", "max_steps_exceeded",
                            "limit", budget.maxSteps()));
        }

        List<SessionEvent> events = log.events(sessionId);
        bus.publish(new TurnFinished(sessionId, status, stepsUsed));
        return new TurnResult(sessionId, status, finalText, stepsUsed, events);
    }

    /** 供观测模块订阅的完成通知。 */
    public record TurnFinished(String sessionId, TurnResult.Status status, int steps) {
    }
}
