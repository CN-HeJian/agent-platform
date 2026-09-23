package com.aplat.tools;

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
 *   <li>策略裁决（工具级权限 + 危险命令）→ {@code BLOCKED_BY_POLICY}</li>
 *   <li>需要批准 → HITL，拒绝则 {@code DENIED}</li>
 *   <li>执行；异常一律兜成 {@code SANDBOX_FAILURE}，不让异常穿透循环</li>
 * </ol>
 *
 * <p>第 3 道曾经写成 {@code if ("shell".equals(call.name()))}——那是个**能绕过的安全检查**：
 * 只要新加一个会执行命令的工具，它就自动失效。现在一律交给 {@link ToolPolicy}，
 * 判定依据是 {@link com.aplat.seam.ToolSpec#commandField()} 这种<b>声明</b>，不是名字。
 *
 * <p>关键取舍：**任何失败都不抛异常**，一律返回结构化结果。异常穿透会杀掉整个 turn，
 * 而模型其实完全有能力根据错误码换个做法。
 */
public final class ToolPipeline {

    private final ToolRegistry registry;
    private final Hitl hitl;
    private final ToolPolicy policy;

    /** 不传策略时用默认策略——**默认仍会做危险命令检查**，不存在"忘了传就裸奔"。 */
    public ToolPipeline(ToolRegistry registry, Hitl hitl) {
        this(registry, hitl, DefaultToolPolicy.defaults());
    }

    public ToolPipeline(ToolRegistry registry, Hitl hitl, ToolPolicy policy) {
        this.registry = registry;
        this.hitl = hitl;
        this.policy = policy;
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

        // 3. 策略裁决：工具级权限 + 危险命令（成本最低、收益最高的一道）
        ToolPolicy.Decision decision = policy.check(sessionId, tool.spec(), call.argumentsJson());
        if (!decision.allowed()) {
            return ToolResult.error(ToolResult.ERR_BLOCKED,
                    decision.code() + ": " + decision.reason());
        }

        // 4. 人工确认（U12）。四种决策各自对应不同后果，所以这里必须分开处理——
        //    把 deny 当"重试"、把 timeout 当"反对"，模型都会做出错误的下一步。
        String effectiveArgs = call.argumentsJson();
        if (tool.approvalRequired()) {
            HitlDecision d = hitl.request(new HitlRequest(
                    sessionId, call.name(), effectiveArgs, approvalReason(tool.spec()), null));
            switch (d) {
                case HitlDecision.Deny deny -> {
                    // 拒绝不是失败终止：当成观察回填，模型有能力换个做法（U13）
                    return ToolResult.error(ToolResult.ERR_DENIED,
                            "user denied execution: " + deny.reason() + ". try another approach.");
                }
                case HitlDecision.Timeout ignored -> {
                    // 与 DENIED 分开：这里的语义是"人不在"，不是"人反对"
                    return ToolResult.error(ToolResult.ERR_TIMEOUT,
                            "no human response in time. abort this action and continue without it.");
                }
                case HitlDecision.Once ignored -> {
                }
                case HitlDecision.Always ignored -> {
                    // "本会话内同类放行"已经记在 Hitl 实现里——只有它才有会话级视图。
                    // 这里刻意不再存一份状态：两份状态迟早会不一致。
                }
                case HitlDecision.Modified m -> {
                    // 改过的参数必须当成**新参数**重新过闸。
                    // 否则"人把命令改掉"就成了绕过策略的后门：批的是 "echo hi"、执行的是 "rm -rf /"。
                    // 用户的意图可信，但参数本身仍需校验——这是两件事。
                    if (!Json.isValidObject(m.newArgumentsJson())) {
                        return ToolResult.error(ToolResult.ERR_INVALID_ARGS,
                                "modified arguments must be a JSON object, got: " + m.newArgumentsJson());
                    }
                    ToolPolicy.Decision recheck = policy.check(sessionId, tool.spec(), m.newArgumentsJson());
                    if (!recheck.allowed()) {
                        return ToolResult.error(ToolResult.ERR_BLOCKED,
                                "modified arguments rejected by policy: " + recheck.code()
                                        + ": " + recheck.reason());
                    }
                    effectiveArgs = m.newArgumentsJson();
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

    /**
     * 给人工确认请求写一句人能看懂的理由。
     *
     * <p>屏幕上只写 "tool requires human approval" 是在浪费这次打扰——人被问到时唯一想知道的
     * 就是"这玩意儿会动什么"。所以这里把"哪个参数会被当命令跑"直接说出来。
     */
    private static String approvalReason(com.aplat.seam.ToolSpec spec) {
        return spec.executesCommands()
                ? "工具 " + spec.name() + " 会执行命令（参数 " + spec.commandField() + "）"
                : "工具 " + spec.name() + " 声明需要人工确认";
    }
}
