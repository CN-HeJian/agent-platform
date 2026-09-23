package com.aplat.run;

import com.aplat.context.BudgetContextProvider;
import com.aplat.durable.DurableRunner;
import com.aplat.durable.StoreCheckpointer;
import com.aplat.durable.StoreIdempotency;
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
public final class Platform implements AutoCloseable {

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
    private final Store store;
    private final DurableRunner durable;
    private final ToolRegistry tools;
    private final List<String> policyWarnings;

    private Platform(Kernel kernel, AgentLoop loop, SessionLog sessionLog, Hitl hitl, Store store,
                     DurableRunner durable, ToolRegistry tools, List<String> policyWarnings) {
        this.kernel = kernel;
        this.loop = loop;
        this.sessionLog = sessionLog;
        this.hitl = hitl;
        this.store = store;
        this.durable = durable;
        this.tools = tools;
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
        return build(llm, sandbox, budget, policy, new InMemoryStore(), log -> hitl, t -> {
        });
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
        return build(llm, sandbox, budget, policy, new InMemoryStore(), hitlFactory, t -> {
        });
    }

    /**
     * 带 <b>Store</b> 的装配（U18）。
     *
     * <p>换持久化的动作只发生在这里：内核、循环、工具、上下文一行都不动——
     * 它们只认 {@link Store} 接口。这就是"可替换"落到实处的样子。
     *
     * <p>不传 Store 的旧重载用 {@link InMemoryStore}，于是
     * <b>不配数据库也能跑</b>（`mvn test`、离线演示都靠它）。
     */
    public static Platform assemble(LlmAdapter llm, Sandbox sandbox, LoopBudget budget,
                                    ToolPolicy policy, Store store,
                                    java.util.function.Function<SessionLog, Hitl> hitlFactory) {
        return build(llm, sandbox, budget, policy, store, hitlFactory, t -> {
        });
    }

    /**
     * 再带一个<b>额外工具</b>的钩子（U30 的入口）。
     *
     * <p>存在的理由很实在：内置工具是固定的三个（echo/add/shell），
     * 而"要验证崩溃恢复没重复副作用"这类事需要一个能被观察的副作用工具；
     * 第三方的插件也需要同一条口子。让它们各造一套装配，不如在这里开一个 Consumer。
     *
     * <p>装配期 lint 在 {@code extraTools} **之后**跑，所以外部工具漏声明
     * {@code commandField} 一样会被喊出来——这是刻意的：插进来的工具没有豁免权。
     */
    public static Platform assemble(LlmAdapter llm, Sandbox sandbox, LoopBudget budget,
                                    ToolPolicy policy, Store store,
                                    java.util.function.Function<SessionLog, Hitl> hitlFactory,
                                    java.util.function.Consumer<ToolRegistry> extraTools) {
        return build(llm, sandbox, budget, policy, store, hitlFactory, extraTools);
    }

    private static Platform build(LlmAdapter llm, Sandbox sandbox, LoopBudget budget,
                                  ToolPolicy policy, Store store,
                                  java.util.function.Function<SessionLog, Hitl> hitlFactory,
                                  java.util.function.Consumer<ToolRegistry> extraTools) {
        ToolRegistry tools = new DefaultToolRegistry();
        BuiltinTools.registerAll(tools);
        tools.register(new ShellTool(sandbox).build());
        extraTools.accept(tools);

        List<String> warnings = DefaultToolPolicy.lint(tools);

        // 先把内核的骨架立起来（Store + SessionLog），才有日志可交给 HITL 工厂
        Kernel.Builder kb = Kernel.builder()
                .bind(Store.class, store)
                .withDefaults();

        Hitl hitl = hitlFactory.apply(kb.sessionLog());

        Kernel kernel = kb
                .bind(LlmAdapter.class, llm)
                .bind(ToolRegistry.class, tools)
                .bind(Sandbox.class, sandbox)
                .bind(ContextProvider.class, new BudgetContextProvider())
                .bind(Hitl.class, hitl)
                .build();

        // 耐久（U19/U20/U21）：检查点与幂等闸都落在同一个 Store 上，所以
        // "换存储"与"要不要耐久"是两件独立的事——前者换实现，后者由 taskId 是否为空决定。
        StoreCheckpointer checkpointer = new StoreCheckpointer(store);
        AgentLoop loop = new AgentLoop(
                kernel.ctx().get(LlmAdapter.class),
                new ToolPipeline(tools, hitl, policy),
                kernel.ctx().get(ContextProvider.class),
                kernel.ctx().get(SessionLog.class),
                kernel.bus(),
                budget,
                DEFAULT_SYSTEM_PROMPT,
                checkpointer,
                new StoreIdempotency(store));

        DurableRunner durable = new DurableRunner(loop, store, checkpointer);
        return new Platform(kernel, loop, kernel.ctx().get(SessionLog.class), hitl, store,
                durable, tools, warnings);
    }

    /**
     * 关掉可关闭的组件。
     *
     * <p>现在只有一个候选人（JDBC Store，将来换上连接池才有事可做），
     * 但入口先留好——"服务停了、数据还留在缓冲里"是那种上线才发现的问题。
     */
    @Override
    public void close() {
        if (store instanceof AutoCloseable closable) {
            try {
                closable.close();
            } catch (Exception e) {
                System.err.println("[platform] 关闭 store 失败: " + e);
            }
        }
    }

    /** 当前装配的持久化实现（健康检查与排查用）。 */
    public Store store() {
        return store;
    }

    /**
     * 耐久执行器（U19/U20）。
     *
     * <p>启动时应当先调一次 {@link DurableRunner#reclaimOrphans()}：
     * 它把上次没跑完的任务标成 {@code CRASHED}，从而让它们可以被续跑。
     */
    public DurableRunner durable() {
        return durable;
    }

    /** 已注册的工具表。给"运行中还想加个工具"的场景与测试用（U30）。 */
    public ToolRegistry tools() {
        return tools;
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
