package com.aplat.run;

import com.aplat.context.BudgetContextProvider;
import com.aplat.kernel.Kernel;
import com.aplat.loop.AgentLoop;
import com.aplat.loop.LoopBudget;
import com.aplat.seam.ContextProvider;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Sandbox;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import com.aplat.seam.ToolRegistry;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.BuiltinTools;
import com.aplat.tools.DefaultToolPolicy;
import com.aplat.tools.DefaultToolRegistry;
import com.aplat.tools.ShellTool;
import com.aplat.tools.ToolPipeline;
import com.aplat.tools.ToolPolicy;
import java.util.List;

/**
 * 装配根（composition root）。
 *
 * <p>整个平台只有**这一处**知道具体实现是谁。换模型、换沙箱、换存储都只改这里；
 * 业务代码（{@link AgentLoop}、工具、上下文）一行不动。
 *
 * <p>换成 Spring 时，这个类就是一份 {@code @Configuration}。
 */
public final class Platform {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个通用 Agent。你可以调用工具来完成任务。
            规则：
            1. 需要外部信息或执行操作时，调用工具，不要凭空编造结果。
            2. 工具返回 ERROR[...] 时，阅读错误码，改用正确的方式重试，不要重复同样的调用。
            3. 任务完成后，用一句话给出最终结论。""";

    private final Kernel kernel;
    private final AgentLoop loop;
    private final SessionLog sessionLog;
    private final Hitl hitl;
    private final List<String> policyWarnings;

    private Platform(Kernel kernel, AgentLoop loop, SessionLog sessionLog, Hitl hitl,
                     List<String> policyWarnings) {
        this.kernel = kernel;
        this.loop = loop;
        this.sessionLog = sessionLog;
        this.hitl = hitl;
        this.policyWarnings = List.copyOf(policyWarnings);
    }

    /** 标准装配：给定模型与沙箱，其余用默认实现补齐。 */
    public static Platform assemble(LlmAdapter llm, Sandbox sandbox, Hitl hitl, LoopBudget budget) {
        return assemble(llm, sandbox, hitl, budget, DefaultToolPolicy.defaults());
    }

    /**
     * 带策略的装配。
     *
     * <p>装配期会跑一次 {@link DefaultToolPolicy#lint}——把"名字像执行类、却没声明 commandField"
     * 的工具报出来。这是对"忘了声明"的唯一可靠兜底：运行时不猜，启动时喊。
     */
    public static Platform assemble(LlmAdapter llm, Sandbox sandbox, Hitl hitl, LoopBudget budget,
                                    ToolPolicy policy) {
        return build(llm, sandbox, budget, policy, log -> hitl);
    }

    /**
     * 带 <b>HITL 工厂</b>的装配。
     *
     * <p>存在的唯一理由是个先后问题：{@link com.aplat.hitl.InteractiveHitl} 要把
     * {@code hitl.requested} 写进**会话日志**，而会话日志是内核在建时创建的——
     * 它没法先于内核被 new 出来。
     *
     * <p>把工厂参数放在装配根里，是为了不让这个顺序依赖渗到别处
     * （比如"先 new 一个空的再回填"那种延迟注入，一旦有人提前用就会静默 NPE）。
     * 业务代码完全不需要知道这里发生过什么。
     */
    public static Platform assemble(LlmAdapter llm, Sandbox sandbox, LoopBudget budget,
                                    ToolPolicy policy,
                                    java.util.function.Function<SessionLog, Hitl> hitlFactory) {
        return build(llm, sandbox, budget, policy, hitlFactory);
    }

    private static Platform build(LlmAdapter llm, Sandbox sandbox, LoopBudget budget,
                                  ToolPolicy policy,
                                  java.util.function.Function<SessionLog, Hitl> hitlFactory) {
        ToolRegistry tools = new DefaultToolRegistry();
        BuiltinTools.registerAll(tools);
        tools.register(new ShellTool(sandbox).build());

        List<String> warnings = DefaultToolPolicy.lint(tools);

        // 先把内核的骨架立起来（Store + SessionLog），才有日志可交给 HITL 工厂
        Kernel.Builder kb = Kernel.builder()
                .bind(Store.class, new InMemoryStore())
                .withDefaults();

        Hitl hitl = hitlFactory.apply(kb.sessionLog());

        Kernel kernel = kb
                .bind(LlmAdapter.class, llm)
                .bind(ToolRegistry.class, tools)
                .bind(Sandbox.class, sandbox)
                .bind(ContextProvider.class, new BudgetContextProvider())
                .bind(Hitl.class, hitl)
                .build();

        AgentLoop loop = new AgentLoop(
                kernel.ctx().get(LlmAdapter.class),
                new ToolPipeline(tools, hitl, policy),
                kernel.ctx().get(ContextProvider.class),
                kernel.ctx().get(SessionLog.class),
                kernel.bus(),
                budget,
                DEFAULT_SYSTEM_PROMPT);

        return new Platform(kernel, loop, kernel.ctx().get(SessionLog.class), hitl, warnings);
    }

    /** 装配期发现的安全隐患（空列表 = 干净）。启动时应当打出来。 */
    public List<String> policyWarnings() {
        return policyWarnings;
    }

    /**
     * 当前装配的 HITL 实现。
     *
     * <p>暴露它的原因很实在：传输层要提供"待确认队列"和"提交决定"两个端点，
     * 而这两件事只有具体的 HITL 实现知道怎么做（{@link Hitl} 本身刻意只有一个
     * 阻塞式 {@code request}）。调用方应当用 {@code instanceof} 判断能力，
     * 而不是假定它一定是哪一种。
     */
    public Hitl hitl() {
        return hitl;
    }

    public AgentLoop loop() {
        return loop;
    }

    public SessionLog sessionLog() {
        return sessionLog;
    }

    public Kernel kernel() {
        return kernel;
    }

    /** 启动时打一行装配清单——排查问题的第一站。 */
    public String assemblyReport() {
        return kernel.assemblyReport();
    }
}
