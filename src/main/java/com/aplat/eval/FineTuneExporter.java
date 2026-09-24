package com.aplat.eval;

import com.aplat.durable.Checkpointer;
import com.aplat.seam.LlmMessage;
import com.aplat.seam.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微调数据导出（U28）：把**通过评测的**轨迹导出成对话格式。
 *
 * <h2>只导出通过的那些，而且这一点要能被检查</h2>
 *
 * <p>微调是在"教模型照这个样子做"。把失败的轨迹也导进去，等于在教它犯错——
 * 而这件事没有报错、没有异常，只是过一阵子发现模型"学会了"某个坏习惯。
 * 所以导出时必须拿到**评测结论**，而不是"跑过的所有会话"。
 *
 * <h2>导出的是消息序列，不是事件流</h2>
 *
 * <p>事件流里有 token 分片、有上下文压缩记录、有 HITL 往返——那些是平台的内部事实，
 * 不是模型该学的对话。微调要的只有一件事：**模型在那一刻看到什么、它给出了什么**。
 * 恰好检查点（U20）里存的就是这个（完整的消息列表），所以导出直接读检查点，
 * 不需要再从事件流里重建一遍。
 */
public final class FineTuneExporter {

    /** 一条训练样本：{@code {"messages":[{"role":...,"content":...}, …]}}。 */
    public record Sample(String caseId, List<LlmMessage> messages) {

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (LlmMessage m : messages) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("role", m.role());
                one.put("content", m.content() == null ? "" : m.content());
                if (m.toolCallId() != null) {
                    one.put("tool_call_id", m.toolCallId());
                }
                if (!m.toolCalls().isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (var c : m.toolCalls()) {
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("id", c.id());
                        call.put("name", c.name());
                        call.put("arguments", c.argumentsJson());
                        calls.add(call);
                    }
                    one.put("tool_calls", calls);
                }
                msgs.add(one);
            }
            out.put("messages", msgs);
            return out;
        }
    }

    public FineTuneExporter() {
    }

    /**
     * 导出通过与失败的对照（{@code skipped} 一并报出来）。
     *
     * <p>返回 {@link Export} 而不是只返回样本列表：**"有多少条被排除掉了、为什么"**
     * 是这份数据能不能用的前提。只给一个文件，没人知道它是不是漏了一半。
     */
    public Export export(EvalRunner.Report report) {
        List<Sample> samples = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (EvalRunner.CaseResult c : report.cases()) {
            if (!c.passed()) {
                excluded.add(c.caseId() + "（评测未通过：" + firstFailure(c) + "）");
                continue;
            }
            var checkpoint = checkpointerOf(report, c.caseId());
            if (checkpoint.isEmpty() || checkpoint.get().messages().isEmpty()) {
                // 通过但没有检查点：通常是"一步就收口、没调工具"的用例。
                // 这种样本对微调几乎没用（它教的是"什么都不做"），排除掉并说明。
                excluded.add(c.caseId() + "（没有检查点：一步收口，没有可学的轨迹）");
                continue;
            }
            samples.add(new Sample(c.caseId(), checkpoint.get().messages()));
        }
        return new Export(report.name(), samples, excluded);
    }

    /**
     * 取某个用例的检查点。
     *
     * <p>从报告里那个 Store 取（见 {@code EvalRunner.Report#stores} 的注释）。
     * 取不到就当成"没有检查点"——**不抛**：导出是一件批处理的事，
     * 一条取不到不该让整份数据集导不出来，但它必须出现在 excluded 里。
     */
    private static java.util.Optional<Checkpointer.Checkpoint> checkpointerOf(
            EvalRunner.Report report, String caseId) {
        Store store = report.storeOf(caseId);
        if (store == null) {
            return java.util.Optional.empty();
        }
        // taskId 的拼法与 EvalRunner 共用一处，避免"写用 A、读用 B"
        return new com.aplat.durable.StoreCheckpointer(store)
                .latest(EvalRunner.taskIdOf(caseId));
    }

    private static String firstFailure(EvalRunner.CaseResult c) {
        return c.failures().isEmpty() ? "未记录原因" : c.failures().get(0);
    }

    /** 导出结果。{@code excluded} 不是日志，是这份数据集的一部分。 */
    public record Export(String datasetName, List<Sample> samples, List<String> excluded) {

        public String toJsonl() {
            StringBuilder sb = new StringBuilder();
            for (Sample s : samples) {
                sb.append(com.aplat.durable.DurableCodecJson.write(s.view())).append('\n');
            }
            return sb.toString();
        }

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dataset", datasetName);
            out.put("samples", samples.size());
            out.put("excluded", excluded);
            out.put("note", "只导出评测通过的轨迹：把失败的也导进去，等于在教模型犯错，"
                    + "而这件事没有报错、没有异常");
            return out;
        }
    }
}
