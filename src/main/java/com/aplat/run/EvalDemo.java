package com.aplat.run;

import com.aplat.durable.DurableCodecJson;
import com.aplat.eval.EvalCase;
import com.aplat.eval.EvalRunner;
import com.aplat.eval.FineTuneExporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测飞轮（U27/U28）的命令行入口。
 *
 * <pre>
 *   ./mvnw -q compile exec:java@eval                    # 跑一遍，与基线对比
 *   ./mvnw -q compile exec:java@eval -Dexec.args="--update-baseline"   # 把当前结果写成新基线
 * </pre>
 *
 * <p>这就是"飞轮"的全部：**改代码 → 跑一遍 → 看哪些用例从绿变红**。
 * 基线文件（{@code eval/baseline.json}）进仓库，于是"这次改动让什么变坏了"
 * 是一个可以 review 的 diff，而不是一句"我本地试过没问题"。
 *
 * <p>三处刻意不粉饰的地方：
 * <ul>
 *   <li>L3 评审**没有评审模型时标成 skipped**，并在输出里单列——绝不算通过；</li>
 *   <li>基线里有、这次没跑的用例会被报出来（删掉一条失败的用例不该让通过率变好看）；</li>
 *   <li>微调导出把**被排除的样本连同原因**一起打出来，而不是只给一个文件。</li>
 * </ul>
 */
public final class EvalDemo {

    private static final Path DATASET = Path.of("eval/dataset-smoke.jsonl");
    private static final Path BASELINE = Path.of("eval/baseline.json");

    public static void main(String[] args) throws Exception {
        boolean updateBaseline = List.of(args).contains("--update-baseline");
        if (!Files.exists(DATASET)) {
            System.err.println("找不到数据集：" + DATASET.toAbsolutePath());
            System.exit(2);
            return;
        }

        List<EvalCase> cases = EvalCase.load(DATASET);
        System.out.println("=== 评测飞轮 ===");
        System.out.println("数据集 : " + DATASET + "（" + cases.size() + " 条）");
        System.out.println("模型   : 脚本化（确定性：同样的代码必然得到同样的结果）");
        System.out.println();

        EvalRunner runner = new EvalRunner();
        EvalRunner.Report report = runner.run(cases, EvalRunner.Options.scripted("smoke"));

        System.out.println("--- 逐条结果 ---");
        for (EvalRunner.CaseResult c : report.cases()) {
            System.out.printf("  %-20s %s  L1=%s L2=%s L3=%s  %dms%n",
                    c.caseId(), c.passed() ? "✅" : "❌",
                    mark(c.l1Passed()), mark(c.l2Passed()),
                    c.l3Passed() == null ? "skip" : mark(c.l3Passed()),
                    c.durationMillis());
            for (String f : c.failures()) {
                System.out.println("      " + f);
            }
            for (String w : c.warnings()) {
                System.out.println("      ⚠ " + w);
            }
        }
        System.out.println();
        System.out.println("通过 " + report.passed() + "/" + report.total()
                + "（" + Math.round(report.passRate() * 100) + "%）"
                + "，L3 未评 " + report.l3Skipped() + " 条"
                + (report.l3Skipped() > 0 ? "（没有配评审模型：**不算通过**）" : ""));
        if (!report.failureBreakdown().isEmpty()) {
            System.out.println("失败分类：" + report.failureBreakdown());
        }

        // ---------------- 与基线对比（U28）----------------
        System.out.println();
        System.out.println("--- 与基线对比 ---");
        Map<String, Boolean> baselineCases = readBaseline();
        if (baselineCases == null) {
            System.out.println("没有基线文件（" + BASELINE + "）。加 --update-baseline 生成第一份。");
        } else {
            EvalRunner.Report baseline = EvalRunner.reportFromBaseline("baseline", baselineCases);
            EvalRunner.Regression regression = EvalRunner.compare(baseline, report);
            System.out.println(regression.summary());
        }
        if (updateBaseline) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", report.name());
            view.put("passRate", Math.round(report.passRate() * 1000) / 1000.0);
            view.put("cases", EvalRunner.baselineOf(report).get("cases"));
            view.put("note", "这是评测基线：只有 caseId → 通过与通过率。"
                    + "改代码后跑 `exec:java@eval` 会与它对比，报出「原本通过、现在失败」的那些。");
            Files.createDirectories(BASELINE.getParent());
            Files.writeString(BASELINE, DurableCodecJson.pretty(view) + "\n", StandardCharsets.UTF_8);
            System.out.println("已写入基线：" + BASELINE);
        }

        // ---------------- 微调导出（U28）----------------
        System.out.println();
        System.out.println("--- 微调数据导出 ---");
        FineTuneExporter.Export export = new FineTuneExporter().export(report);
        System.out.println("样本数 : " + export.samples().size());
        for (String e : export.excluded()) {
            System.out.println("  排除 : " + e);
        }
        Path out = Path.of("target/finetune.jsonl");
        Files.createDirectories(out.getParent());
        Files.writeString(out, export.toJsonl(), StandardCharsets.UTF_8);
        System.out.println("已写入 : " + out);
        if (!export.samples().isEmpty()) {
            System.out.println("样本首条（截断）: "
                    + export.toJsonl().lines().findFirst().orElse("").substring(0,
                            Math.min(160, export.toJsonl().lines().findFirst().orElse("").length())) + "…");
        }
    }

    private static String mark(boolean ok) {
        return ok ? "ok" : "FAIL";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> readBaseline() throws Exception {
        if (!Files.exists(BASELINE)) {
            return null;
        }
        Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(Files.readString(BASELINE, StandardCharsets.UTF_8), Map.class);
        Object cases = parsed.get("cases");
        if (!(cases instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), Boolean.TRUE.equals(v)));
        return out;
    }

    private EvalDemo() {
    }
}
