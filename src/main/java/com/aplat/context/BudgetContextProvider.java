package com.aplat.context;

import com.aplat.seam.ContextProvider;
import com.aplat.seam.LlmMessage;
import com.aplat.seam.PreparedContext;
import com.aplat.seam.PreparedContext.BlockScore;
import com.aplat.seam.PreparedContext.DroppedBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * 预算裁剪 + 分层压缩的实现。
 *
 * <p>三层策略，从"最不伤信息"到"最伤信息"依次尝试，够用就停：
 * <ol>
 *   <li><b>截断</b>：超长工具输出原地截断（保留头尾），信息损失最小</li>
 *   <li><b>降级</b>：老的工具往返对压成一行摘要（工具名 + 结果长度），保留"做过什么"</li>
 *   <li><b>丢弃</b>：仍然超预算才丢最老的非 system 消息，**并且逐条记录**</li>
 * </ol>
 *
 * <p>两条不可动摇的规则：
 * <ul>
 *   <li>system 永不丢（否则角色设定会悄悄消失）</li>
 *   <li>被丢的东西必须出现在 {@code dropped} 里——"模型忘了早期约束"要靠这份记录定位</li>
 * </ul>
 *
 * <p>token 估算用 chars/4 的启发式：不需要精确（精确要调 tokenizer），
 * 但足以在超预算前触发裁剪。真实计费以 provider 返回的 usage 为准。
 */
public final class BudgetContextProvider implements ContextProvider {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int MAX_TOOL_OUTPUT_CHARS = 2_000;
    private static final int TRUNCATE_HEAD = 1_200;
    private static final int TRUNCATE_TAIL = 400;
    private static final int RECENT_TURNS_KEPT = 6;

    @Override
    public String id() {
        return "context.budget";
    }

    @Override
    public PreparedContext prepare(List<LlmMessage> history, int budgetTokens) {
        List<DroppedBlock> dropped = new ArrayList<>();
        List<BlockScore> scores = new ArrayList<>();

        // 第 1 层：截断超长工具输出
        List<LlmMessage> working = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            LlmMessage m = history.get(i);
            if ("tool".equals(m.role()) && m.content() != null && m.content().length() > MAX_TOOL_OUTPUT_CHARS) {
                String shortened = headTail(m.content());
                dropped.add(new DroppedBlock("TRUNCATED", i,
                        estimate(m.content().length()) - estimate(shortened.length()),
                        preview(m.content())));
                working.add(new LlmMessage(m.role(), shortened, m.toolCallId(), m.toolCalls()));
            } else {
                working.add(m);
            }
        }

        // 第 2 层：老的（非 system、非最近 N 条）工具往返降级成一行
        if (estimateAll(working) > budgetTokens) {
            int cutoff = Math.max(0, working.size() - RECENT_TURNS_KEPT);
            for (int i = 0; i < cutoff; i++) {
                LlmMessage m = working.get(i);
                if ("tool".equals(m.role()) && m.content() != null && m.content().length() > 200) {
                    String digest = "[tool output elided, " + m.content().length() + " chars] "
                            + preview(m.content());
                    dropped.add(new DroppedBlock("DIGESTED", i,
                            estimate(m.content().length()) - estimate(digest.length()), preview(m.content())));
                    working.set(i, new LlmMessage(m.role(), digest, m.toolCallId(), m.toolCalls()));
                }
            }
        }

        // 第 3 层：仍超预算则从最老的非 system 开始丢，逐条记录
        while (estimateAll(working) > budgetTokens) {
            int victim = -1;
            for (int i = 0; i < working.size(); i++) {
                if (!"system".equals(working.get(i).role())) {
                    victim = i;
                    break;
                }
            }
            if (victim < 0) {
                break; // 只剩 system，无法再压
            }
            LlmMessage removed = working.remove(victim);
            dropped.add(new DroppedBlock("DROPPED_OLDEST", victim,
                    estimate(removed.content() == null ? 0 : removed.content().length()),
                    preview(removed.content())));
        }

        // 逐块打分：越靠后（越近）越重要，system 最高
        for (int i = 0; i < working.size(); i++) {
            LlmMessage m = working.get(i);
            double score;
            String reason;
            if ("system".equals(m.role())) {
                score = 1.0;
                reason = "system prompt: never dropped";
            } else {
                int distanceFromEnd = working.size() - 1 - i;
                score = Math.max(0.05, 1.0 - distanceFromEnd * 0.08);
                reason = "recency-weighted (distance " + distanceFromEnd + ")";
            }
            scores.add(new BlockScore(i, round(score), reason));
        }

        int total = estimateAll(working);
        return new PreparedContext(working, dropped, scores, total, !dropped.isEmpty());
    }

    private static String headTail(String s) {
        if (s.length() <= TRUNCATE_HEAD + TRUNCATE_TAIL) {
            return s;
        }
        return s.substring(0, TRUNCATE_HEAD)
                + "\n... [middle truncated " + (s.length() - TRUNCATE_HEAD - TRUNCATE_TAIL) + " chars] ...\n"
                + s.substring(s.length() - TRUNCATE_TAIL);
    }

    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ');
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "...";
    }

    private static int estimate(int chars) {
        return chars / CHARS_PER_TOKEN;
    }

    public static int estimateAll(List<LlmMessage> messages) {
        int sum = 0;
        for (LlmMessage m : messages) {
            sum += estimate(m.content() == null ? 0 : m.content().length());
            sum += 4; // 角色与结构开销
        }
        return sum;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
