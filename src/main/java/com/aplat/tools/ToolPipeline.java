package com.aplat.tools;

import com.aplat.sandbox.CommandPolicy;
import com.aplat.seam.Hitl;
import com.aplat.seam.HitlDecision;
import com.aplat.seam.HitlRequest;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolResult;

/**
 * 工具执行管线：模型"想调用"到"真的执行"之间的全部判断都集中在这里。
 *
 * <p>五道判断，顺序有意义——便宜的、能拦住危险的放前面，需要打扰人的放后面：
 * <ol>
 *   <li>工具不存在 → {@code UNKNOWN_TOOL}（幻觉防护，回给模型自纠）</li>
 *   <li>参数不是合法 JSON 对象 → {@code INVALID_ARGS}</li>
 *   <li>策略拦截（危险命令）→ {@code BLOCKED_BY_POLICY}</li>
 *   <li>需要批准 → HITL，拒绝则 {@code DENIED}</li>
 *   <li>执行；异常一律兜成 {@code SANDBOX_FAILURE}，不让异常穿透循环</li>
 * </ol>
 *
 * <p>关键取舍：**任何失败都不抛异常**，一律返回结构化结果。异常穿透会杀掉整个 turn，
 * 而模型其实完全有能力根据错误码换个做法。
 */
public final class ToolPipeline {

    private final ToolRegistry registry;
    private final Hitl hitl;

    public ToolPipeline(ToolRegistry registry, Hitl hitl) {
        this.registry = registry;
        this.hitl = hitl;
    }

    /** 暴露当前可用工具的 schema，供循环注入到 LLM 请求里。 */
    public java.util.List<com.aplat.seam.ToolSpec> specsView() {
        return registry.specs();
    }

    public ToolResult execute(String sessionId, ToolCall call) {
        // 1. 幻觉防护：工具不存在
        var found = registry.find(call.name());
        if (found.isEmpty()) {
            return ToolResult.error(ToolResult.ERR_UNKNOWN_TOOL,
                    "no such tool: '" + call.name() + "'. available tools: " + registry.specs().stream()
                            .map(s -> s.name()).toList());
        }
        Tool tool = found.get();

        // 2. 参数结构
        if (!Json.isValidObject(call.argumentsJson())) {
            return ToolResult.error(ToolResult.ERR_INVALID_ARGS,
                    "arguments must be a JSON object, got: " + call.argumentsJson());
        }

        // 3. 策略拦截（成本最低、收益最高的一道）
        if ("shell".equals(call.name())) {
            String command = Json.argString(call.argumentsJson(), "command");
            CommandPolicy.Decision decision = CommandPolicy.check(command);
            if (!decision.allowed()) {
                return ToolResult.error(ToolResult.ERR_BLOCKED, decision.reason());
            }
        }

        // 4. 人工确认
        String effectiveArgs = call.argumentsJson();
        if (tool.approvalRequired()) {
            HitlDecision d = hitl.request(new HitlRequest(
                    sessionId, call.name(), effectiveArgs, "tool requires human approval", null));
            switch (d) {
                case HitlDecision.Deny deny -> {
                    return ToolResult.error(ToolResult.ERR_DENIED,
                            "user denied execution: " + deny.reason() + ". try another approach.");
                }
                case HitlDecision.Timeout ignored -> {
                    return ToolResult.error(ToolResult.ERR_TIMEOUT,
                            "no human response in time. abort this action and continue without it.");
                }
                case HitlDecision.Modified m -> effectiveArgs = m.newArgumentsJson();
                case HitlDecision.Once ignored -> {
                }
                case HitlDecision.Always ignored -> {
                }
            }
        }

        // 5. 执行
        try {
            return tool.handler().handle(new ToolCall(call.id(), call.name(), effectiveArgs));
        } catch (Exception e) {
            return ToolResult.error(ToolResult.ERR_SANDBOX,
                    "tool threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
