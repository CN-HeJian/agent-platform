package com.aplat.loop;

import com.aplat.durable.Checkpointer;
import com.aplat.durable.IdempotencyGuard;
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
import java.util.Optional;

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
 *
 * <h2>耐久（U20/U21）是怎么接进来的</h2>
 *
 * <p>循环本身仍然"无状态"：它只在每个 step 末尾调一次
 * {@link Checkpointer#checkpoint}，以及在执行工具前问一次 {@link IdempotencyGuard}。
 * 两者都有 noop 实现，所以不需要恢复的场景（同步调用、单测）行为一字未变。
 *
 * <p><b>为什么检查点存的是完整消息列表而不是步号</b>：续跑时模型要和崩溃前看到一模一样的
 * 上下文（包括工具返回的观察）。只存步号等于让模型失忆重来——那还不如不恢复。
 *
 * <p><b>为什么要在执行工具前"声明"</b>：进程可能崩在"命令跑了"与"检查点写了"之间。
 * 声明 + 回填结果让续跑能分辨三种情形（见 {@link IdempotencyGuard}），
 * 从而做到"副作用至多一次"，而不是"尽量少重复"。
 *
 * <p>{@code taskId} 为 null 时，检查点与幂等都不启用——这条路径与加耐久之前完全一致。
 */
public final class AgentLoop {

    private final LlmAdapter llm;
    private final ToolPipeline pipeline;
    private final ContextProvider context;
    private final SessionLog log;
    private final EventBus bus;
    private final LoopBudget budget;
    private final String systemPrompt;
    private final Checkpointer checkpointer;
    private final IdempotencyGuard idempotency;

    public AgentLoop(LlmAdapter llm,
                     ToolPipeline pipeline,
                     ContextProvider context,
                     SessionLog log,
                     EventBus bus,
                     LoopBudget budget,
                     String systemPrompt) {
        this(llm, pipeline, context, log, bus, budget, systemPrompt,
                Checkpointer.noop(), IdempotencyGuard.none());
    }

    public AgentLoop(LlmAdapter llm,
                     ToolPipeline pipeline,
                     ContextProvider context,
                     SessionLog log,
                     EventBus bus,
                     LoopBudget budget,
                     String systemPrompt,
                     Checkpointer checkpointer,
                     IdempotencyGuard idempotency) {
        this.llm = llm;
        this.pipeline = pipeline;
        this.context = context;
        this.log = log;
        this.bus = bus;
        this.budget = budget;
        this.systemPrompt = systemPrompt;
        this.checkpointer = checkpointer;
        this.idempotency = idempotency;
    }

    public SessionLog sessionLog() {
        return log;
    }

    public LoopBudget budget() {
        return budget;
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
        return run(sessionId, history, userInput, null);
    }

    /**
     * 带 <b>taskId</b> 的 run：启用检查点与幂等。
     *
     * @param taskId null = 不启用耐久（行为与之前完全一致）
     */
    public TurnResult run(String sessionId, List<LlmMessage> history, String userInput, String taskId) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        messages.addAll(history);
        messages.add(LlmMessage.user(userInput));

        log.append(sessionId, SessionLog.EV_INPUT,
                Map.of("text", userInput, "historyTurns", history.size()));
        log.append(sessionId, SessionLog.EV_TURN_START, Map.of("input", userInput));
        return drive(sessionId, taskId, messages, 1, null);
    }

    /**
     * 从检查点续跑（U20）。
     *
     * <p>严格从 {@code checkpoint.step() + 1} 开始：已经跑完的步骤**不重跑**，
     * 因为它们的观察已经在检查点的消息里了。这正是"断点续跑"与"重试"的区别。
     *
     * <p>若检查点已经用完了预算（{@code step >= maxSteps}），这里会直接以
     * {@code MAX_STEPS} 收口而不是空转——并且事件里写明原因是"续跑时预算已尽"，
     * 免得看起来像循环坏了。
     */
    public TurnResult resume(String sessionId, Checkpointer.Checkpoint checkpoint, String taskId) {
        List<LlmMessage> messages = new ArrayList<>(checkpoint.messages());
        int firstStep = checkpoint.step() + 1;
        log.append(sessionId, SessionLog.EV_TURN_RESUMED,
                Map.of("fromStep", firstStep, "restoredMessages", messages.size(),
                        "taskId", taskId == null ? "" : taskId));

        if (firstStep > budget.maxSteps()) {
            log.append(sessionId, SessionLog.EV_TURN_CLOSED,
                    Map.of("step", checkpoint.step(), "reason", "budget_exhausted_on_resume",
                            "limit", budget.maxSteps()));
            return new TurnResult(sessionId, TurnResult.Status.MAX_STEPS, null,
                    checkpoint.step(), log.events(sessionId));
        }
        return drive(sessionId, taskId, messages, firstStep, checkpoint);
    }

    // ---------------------------------------------------------------- 主体

    private TurnResult drive(String sessionId, String taskId, List<LlmMessage> messages,
                             int firstStep, Checkpointer.Checkpoint resumedFrom) {
        String finalText = null;
        TurnResult.Status status = TurnResult.Status.MAX_STEPS;
        int stepsUsed = resumedFrom == null ? 0 : resumedFrom.step();

        for (int step = firstStep; step <= budget.maxSteps(); step++) {
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

                ToolResult result = executeGuarded(sessionId, taskId, step, call);

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

            // 6) 检查点（U20）：存**完整消息**，续跑时模型看到的上下文与此刻一致
            if (taskId != null) {
                checkpointer.checkpoint(taskId, step, messages);
            }
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

    /**
     * 执行一次工具调用，带幂等闸（U21）。
     *
     * <p>没开耐久（{@code taskId == null}）时直接执行，一行附加行为都没有。
     */
    private ToolResult executeGuarded(String sessionId, String taskId, int step, ToolCall call) {
        if (taskId == null) {
            return pipeline.execute(sessionId, call);
        }
        // 键里必须带 step 与 callId：同一步里的多次不同调用、不同步里的同名调用，都是不同的副作用
        String key = taskId + "#" + step + "#" + call.id();
        Optional<ToolResult> already = idempotency.claim(key);
        if (already.isPresent()) {
            // 回放：**没有真的执行**。这条事件让"这一步省了一次副作用"变得可见——
            // 否则从事件流看，和真跑了一遍完全一样。
            log.append(sessionId, SessionLog.EV_TOOL_REPLAYED, Map.of(
                    "step", step, "id", String.valueOf(call.id()),
                    "tool", String.valueOf(call.name()),
                    "reason", IdempotencyGuard.ERR_DUPLICATE_SUPPRESSED.equals(already.get().errorCode())
                            ? "result-unknown" : "replayed-previous-result"));
            return already.get();
        }
        ToolResult result = pipeline.execute(sessionId, call);
        idempotency.complete(key, result);
        return result;
    }

    /** 供观测模块订阅的完成通知。 */
    public record TurnFinished(String sessionId, TurnResult.Status status, int steps) {
    }
}
