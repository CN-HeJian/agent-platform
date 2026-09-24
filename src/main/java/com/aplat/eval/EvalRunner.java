package com.aplat.eval;

import com.aplat.loop.LoopBudget;
import com.aplat.loop.TurnResult;
import com.aplat.run.Platform;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.SessionLog;
import com.aplat.seam.Store;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolRegistry;
import com.aplat.seam.ToolSpec;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;
import com.aplat.tools.DefaultToolPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 评测执行器（U27）：跑一个数据集，给出**分级的**结论。
 *
 * <h2>三级评估器，各自回答不同的问题</h2>
 *
 * <table border="1">
 *   <tr><th>级别</th><th>看什么</th><th>能证明什么</th></tr>
 *   <tr><td>L1 规则</td><td>状态、工具调用集合、禁止调用、步数</td>
 *       <td>确定性断言。**这一级失败就是真失败**</td></tr>
 *   <tr><td>L2 文本</td><td>最终答复是否包含期望片段</td>
 *       <td>表述层面的约束。它比 L1 脆（换个措辞就红），所以单独计</td></tr>
 *   <tr><td>L3 评审</td><td>交给另一个模型按评分标准判断</td>
 *       <td>主观质量的趋势。**没有评审模型时标成 skipped，绝不算通过**</td></tr>
 * </table>
 *
 * <p>分级而不是给一个总分：一个总分掩盖的是"到底哪一类坏了"。上面三种失败的处理方式完全不同——
 * L1 挂了要立刻回滚，L2 挂了可能只是措辞变了，L3 挂了通常只是趋势。
 *
 * <h2>每个用例一个全新的 Store</h2>
 *
 * <p>用例之间共享状态会让"第 3 条为什么挂了"取决于"第 2 条跑了什么"。
 * 用内存 Store（{@link InMemoryStore}）而不是生产库：评测是**离线**的，
 * 它不该往生产库里塞几百条假会话。
 */
public final class EvalRunner {

    /**
     * 一个用例的评估结果。
     *
     * <p>{@code passed} 的口径：**L1 与 L2 都过，且 L3 没有明确判 FAIL**。
     * 也就是："没评"不拉低结论（那会把没配评审模型的跑法变成全红），
     * 而"评审明确说不行"要拉低（否则那个判断就没有任何作用）。
     */
    public record CaseResult(
            String caseId,
            boolean passed,
            boolean l1Passed,
            boolean l2Passed,
            Boolean l3Passed,
            List<String> failures,
            List<String> warnings,
            String status,
            List<String> actualTools,
            int steps,
            String finalText,
            long durationMillis) {

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("caseId", caseId);
            out.put("passed", passed);
            out.put("l1", l1Passed);
            out.put("l2", l2Passed);
            out.put("l3", l3Passed);
            out.put("status", status);
            out.put("steps", steps);
            out.put("actualTools", actualTools);
            out.put("durationMillis", durationMillis);
            if (!failures.isEmpty()) {
                out.put("failures", failures);
            }
            if (!warnings.isEmpty()) {
                out.put("warnings", warnings);
            }
            return out;
        }
    }

    /** 一次评测的汇总。 */
    public record Report(
            String name,
            long startedAt,
            List<CaseResult> cases,
            int l3Skipped,
            /**
             * 每个用例跑完之后的 Store。
             *
             * <p>为什么评测报告里要带着它：微调导出（U28）要读**检查点**才能拿到
             * "模型当时看到了什么"，而检查点就在这个 Store 里。让导出方自己再跑一遍
             * 显然是错的（那会把同一批用例跑两次）；重新从事件流重建检查点也不可能
             * （事件流里只有分片与工具结果，恢复不出完整的消息列表）。
             *
             * <p>它只活在内存里，序列化 {@link #view()} 时刻意不包含它——
             * 报告要能落盘、要能进 diff，而 Store 不能。
             */
            Map<String, Store> stores) {

        /** 某个用例的 Store（没有则 null）。 */
        public Store storeOf(String caseId) {
            return stores.get(caseId);
        }

        public int total() {
            return cases.size();
        }

        public int passed() {
            return (int) cases.stream().filter(CaseResult::passed).count();
        }

        public int failed() {
            return total() - passed();
        }

        public double passRate() {
            return total() == 0 ? 0 : (double) passed() / total();
        }

        /** 按"哪一级挂了"分类——比一个通过率有用得多。 */
        public Map<String, Integer> failureBreakdown() {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (CaseResult c : cases) {
                if (c.passed()) {
                    continue;
                }
                String key = !c.l1Passed() ? "L1-规则"
                        : (!c.l2Passed() ? "L2-文本" : "L3-评审");
                out.merge(key, 1, Integer::sum);
            }
            return out;
        }

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("startedAt", startedAt);
            out.put("total", total());
            out.put("passed", passed());
            out.put("failed", failed());
            out.put("passRate", Math.round(passRate() * 1000) / 1000.0);
            out.put("l3Skipped", l3Skipped);
            out.put("failureBreakdown", failureBreakdown());
            List<Map<String, Object>> rows = new ArrayList<>();
            cases.forEach(c -> rows.add(c.view()));
            out.put("cases", rows);
            return out;
        }
    }

    /** 跑一次评测要用什么。全部给默认值也能跑——评测不该有"还没配好所以跑不了"这个状态。 */
    public record Options(
            String name,
            int maxSteps,
            Function<EvalCase, LlmAdapter> adapterFactory,
            Function<EvalCase, LlmAdapter> judgeFactory,
            Consumer<ToolRegistry> extraTools) {

        public static Options scripted(String name) {
            return new Options(name, 4, EvalCase::asAdapter, null, t -> {
            });
        }

        public static Options scripted(String name, int maxSteps) {
            return new Options(name, maxSteps, EvalCase::asAdapter, null, t -> {
            });
        }

        public Options withJudge(Function<EvalCase, LlmAdapter> judge) {
            return new Options(name, maxSteps, adapterFactory, judge, extraTools);
        }

        public Options withTools(Consumer<ToolRegistry> tools) {
            return new Options(name, maxSteps, adapterFactory, judgeFactory, tools);
        }
    }

    /**
     * 一个用例对应的 taskId。
     *
     * <p>集中在这里而不是各写各的：检查点按 taskId 索引，写的时候用 "eval-" + id、
     * 读的时候用 id，就会出现"导出的样本数是 0，而原因看起来像是'这条用例没有轨迹'"——
     * 这个坑本轮踩过一次（见 {@code FineTuneExporter}）。
     */
    public static String taskIdOf(String caseId) {
        return "eval-" + caseId;
    }

    public Report run(List<EvalCase> cases, Options options) {
        long startedAt = System.currentTimeMillis();
        List<CaseResult> results = new ArrayList<>();
        int l3Skipped = 0;

        Map<String, Store> stores = new LinkedHashMap<>();
        for (EvalCase c : cases) {
            InMemoryStore store = new InMemoryStore();
            CaseResult result = runOne(c, options, store);
            stores.put(c.id(), store);
            if (result.l3Passed() == null) {
                l3Skipped++;
            }
            results.add(result);
        }
        return new Report(options.name(), startedAt, results, l3Skipped, stores);
    }

    private CaseResult runOne(EvalCase c, Options options, Store store) {
        int maxSteps = c.maxSteps() > 0 ? c.maxSteps() : options.maxSteps();
        LlmAdapter llm = options.adapterFactory().apply(c);

        long t0 = System.nanoTime();
        TurnResult result;
        String status;
        try {
            Platform platform = Platform.assemble(llm, new ProcessSandbox(),
                    new LoopBudget(maxSteps, 8_000), DefaultToolPolicy.defaults(), store,
                    log -> Hitl.autoAllow(), options.extraTools());
            // 用**带 taskId 的 run**：这是生产的真实路径（含检查点与幂等闸），
            // 而不是一条只在评测里存在的简化路径。两个理由：
            //   1) 评测要评的是真跑起来的那条路，否则它给的绿灯没有意义；
            //   2) 微调导出（U28）要读检查点——那是"模型当时看到了什么"的权威记录，
            //      而它只在耐久路径上才有。（一开始我用的是无 taskId 的 run，
            //      结果导出把所有用例都当成"没有可学的轨迹"排除了。）
            result = platform.loop().run(c.sessionId(), List.of(), c.input(), taskIdOf(c.id()));
            status = result.status().name();
            platform.close();
        } catch (Exception e) {
            // 用例抛出异常是**结果**，不是崩评测程序。一条用例把整轮评测带走的话，
            // 你会看不到后面所有用例的结果——而那通常是更需要的部分。
            return new CaseResult(c.id(), false, false, false, null,
                    List.of("用例执行抛异常: " + e.getClass().getSimpleName() + " " + e.getMessage()),
                    List.of(), "THREW", List.of(), 0, "", (System.nanoTime() - t0) / 1_000_000);
        }

        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        var log = new EventSourcedSessionLog(store);
        List<String> actualTools = log.ofType(c.sessionId(), SessionLog.EV_TOOL_CALL).stream()
                .map(e -> String.valueOf(e.get("tool"))).toList();
        // "失败原因"= 错误码 + 原因文本。原因文本里有**具体是哪条规则**拦的
        // （errorCode 只有通用的 BLOCKED_BY_POLICY），而"哪条规则"才是要断言的东西。
        List<String> actualFailures = log.ofType(c.sessionId(), SessionLog.EV_TOOL_RESULT).stream()
                .filter(e -> !"true".equals(String.valueOf(e.get("ok"))))
                .map(e -> String.valueOf(e.get("errorCode")) + " " + String.valueOf(e.get("content")))
                .toList();
        String finalText = result.finalText() == null ? "" : result.finalText();

        // ---------------- L1：确定性规则 ----------------
        if (!c.expectStatus().isEmpty() && !c.expectStatus().contains(status)) {
            failures.add("L1 状态不符：期望 " + c.expectStatus() + "，实际 " + status);
        }
        Set<String> actual = new LinkedHashSet<>(actualTools);
        for (String expected : c.expectTools()) {
            if (!actual.contains(expected)) {
                failures.add("L1 期望调用工具 '" + expected + "'，实际调用的是 " + actualTools);
            }
        }
        for (String forbidden : c.forbidTools()) {
            if (actual.contains(forbidden)) {
                failures.add("L1 **不该调用的工具被调用了**：'" + forbidden + "'。"
                        + "这是安全回归，不是功能问题");
            }
        }
        for (String expectedFailure : c.expectToolFailures()) {
            if (actualFailures.stream().noneMatch(f -> f.contains(expectedFailure))) {
                failures.add("L1 期望工具失败原因里出现 '" + expectedFailure + "'，实际失败的是 " + actualFailures
                        + "。这一条通常意味着某道检查**不再生效**了——"
                        + "工具照样被调用、措辞也可能没变，但结果里没有那个拒绝");
            }
        }
        if (result.steps() > maxSteps) {
            failures.add("L1 步数超预算：" + result.steps() + " > " + maxSteps);
        }
        boolean l1Passed = failures.isEmpty();

        // ---------------- L2：文本包含 ----------------
        List<String> l2Failures = new ArrayList<>();
        for (String fragment : c.expectText()) {
            if (!finalText.contains(fragment)) {
                l2Failures.add("L2 最终答复里没有出现 '" + fragment + "'，实际：" + abbreviate(finalText));
            }
        }
        failures.addAll(l2Failures);
        boolean l2Passed = l2Failures.isEmpty();

        // ---------------- L3：模型评审 ----------------
        Boolean l3Passed = null;
        if (options.judgeFactory() != null) {
            try {
                l3Passed = judge(c, options, log, finalText);
                if (Boolean.FALSE.equals(l3Passed)) {
                    failures.add("L3 评审模型判为不通过");
                }
            } catch (Exception e) {
                // 评审自己出问题，不能算通过，也不能算用例失败——单列成警告并保持"未评"。
                l3Passed = null;
                warnings.add("L3 评审未能完成：" + e.getMessage() + "（标为 skipped，不计入通过）");
            }
        }

        long millis = (System.nanoTime() - t0) / 1_000_000;
        boolean overall = l1Passed && l2Passed && !Boolean.FALSE.equals(l3Passed);
        return new CaseResult(c.id(), overall, l1Passed, l2Passed, l3Passed,
                List.copyOf(failures), List.copyOf(warnings), status, actualTools,
                result.steps(), finalText, millis);
    }

    /**
     * 让另一个模型按评分标准判断。
     *
     * <p>提示词里**只给事实**（输入、实际调用的工具、最终答复），不给"应该是怎样"——
     * 否则评审模型会倾向于附和期望。评分标准要求它先给理由再给结论，
     * 因为"先下结论再找理由"是这类评审最常见的失效方式。
     */
    private Boolean judge(EvalCase c, Options options, EventSourcedSessionLog log, String finalText)
            throws Exception {
        LlmAdapter judgeLlm = options.judgeFactory().apply(c);
        String prompt = String.join("\n",
                "你在评审一次 Agent 执行结果。只判断，不要执行任何工具。",
                "",
                "用户的请求：" + c.input(),
                "实际调用的工具（按顺序）：" + log.ofType(c.sessionId(), SessionLog.EV_TOOL_CALL)
                        .stream().map(e -> String.valueOf(e.get("tool"))).toList(),
                "最终答复：" + finalText,
                "",
                "评分标准：这个答复是否真正回答了用户、且没有做出与请求无关的动作？",
                "先写一行「理由：」，再写一行「结论：PASS」或「结论：FAIL」。");

        StringBuilder answer = new StringBuilder();
        judgeLlm.stream(com.aplat.seam.LlmRequest.of("", List.of(com.aplat.seam.LlmMessage.user(prompt)),
                        List.<ToolSpec>of()), chunk -> {
            if (chunk instanceof com.aplat.seam.LlmChunk.TextDelta d) {
                answer.append(d.text());
            }
        });
        String text = answer.toString();
        if (text.contains("结论：PASS") || text.equalsIgnoreCase("PASS")) {
            return Boolean.TRUE;
        }
        if (text.contains("结论：FAIL") || text.equalsIgnoreCase("FAIL")) {
            return Boolean.FALSE;
        }
        throw new IllegalStateException("评审模型没有给出明确的 PASS/FAIL：" + abbreviate(text));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    // ------------------------------------------------------------------ 回归对比

    /** 两次评测之间的差异（U28）。 */
    public record Regression(
            String baselineName,
            String currentName,
            List<String> newlyFailing,
            List<String> newlyPassing,
            List<String> stillFailing,
            double passRateDelta) {

        public boolean hasRegression() {
            return !newlyFailing.isEmpty();
        }

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("baseline", baselineName);
            out.put("current", currentName);
            out.put("passRateDelta", Math.round(passRateDelta * 1000) / 1000.0);
            out.put("regression", hasRegression());
            out.put("newlyFailing", newlyFailing);
            out.put("newlyPassing", newlyPassing);
            out.put("stillFailing", stillFailing);
            return out;
        }

        /** 给人看的一段话。**先说坏消息**——这是这类输出唯一重要的排布原则。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("通过率: ").append(baselineName).append(" → ").append(currentName)
                    .append("  ").append(Math.round(passRateDelta * 1000) / 1000.0)
                    .append(passRateDelta < 0 ? "（下降）" : passRateDelta > 0 ? "（上升）" : "（持平）");
            if (hasRegression()) {
                sb.append("\n**回归（原本通过、现在失败）**：").append(newlyFailing);
                sb.append("\n  这一类必须先处理：它是「我改坏了什么」。");
            }
            if (!newlyPassing.isEmpty()) {
                sb.append("\n新通过：").append(newlyPassing);
            }
            if (!stillFailing.isEmpty()) {
                sb.append("\n一直失败（非本轮引入）：").append(stillFailing);
            }
            if (!hasRegression() && newlyPassing.isEmpty() && stillFailing.isEmpty()) {
                sb.append("\n与基线完全一致。");
            }
            return sb.toString();
        }
    }

    public static Regression compare(Report baseline, Report current) {
        Map<String, Boolean> was = new LinkedHashMap<>();
        baseline.cases().forEach(c -> was.put(c.caseId(), c.passed()));

        List<String> newlyFailing = new ArrayList<>();
        List<String> newlyPassing = new ArrayList<>();
        List<String> stillFailing = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (CaseResult c : current.cases()) {
            seen.add(c.caseId());
            Boolean before = was.get(c.caseId());
            if (before == null) {
                // 基线里没有的新用例：不算回归也不算新通过，但要说出来
                continue;
            }
            if (before && !c.passed()) {
                newlyFailing.add(c.caseId());
            } else if (!before && c.passed()) {
                newlyPassing.add(c.caseId());
            } else if (!before) {
                stillFailing.add(c.caseId());
            }
        }
        List<String> disappeared = new ArrayList<>(was.keySet());
        disappeared.removeAll(seen);
        if (!disappeared.isEmpty()) {
            // 基线里有、这次没跑的用例：这才是"评测少跑了一条"的真实表现。
            // 不报出来的话，删掉一个失败的用例会让通过率悄悄变好看。
            stillFailing.add("(基线里有但本次未运行) " + disappeared);
        }
        return new Regression(baseline.name(), current.name(), newlyFailing, newlyPassing,
                stillFailing, current.passRate() - baseline.passRate());
    }

    /** 给"基线文件"用的读写：只存 caseId → passed 与通过率，便于人工看 diff。 */
    public static Map<String, Object> baselineOf(Report report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", report.name());
        out.put("passRate", Math.round(report.passRate() * 1000) / 1000.0);
        Map<String, Boolean> cases = new LinkedHashMap<>();
        report.cases().forEach(c -> cases.put(c.caseId(), c.passed()));
        out.put("cases", cases);
        return out;
    }

    /** 从基线文件恢复成"假 Report"以便复用 {@link #compare}。 */
    public static Report reportFromBaseline(String name, Map<String, Boolean> cases) {
        List<CaseResult> results = new ArrayList<>();
        cases.forEach((id, passed) -> results.add(new CaseResult(id, passed, passed, passed, null,
                List.of(), List.of(), "BASELINE", List.of(), 0, "", 0)));
        return new Report(name, 0, results, 0, Map.of());
    }

}
