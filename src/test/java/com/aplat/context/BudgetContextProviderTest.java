package com.aplat.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.LlmMessage;
import com.aplat.seam.PreparedContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩：验收标准是"不静默丢东西"——每一块为什么留、为什么丢都能查。
 * 这比"压得够小"重要得多，因为线上问题都是"模型怎么忘了"，不是"怎么超预算了"。
 */
class BudgetContextProviderTest {

    private final BudgetContextProvider provider = new BudgetContextProvider();

    @Test
    @DisplayName("预算充足时原样通过，不制造无谓的压缩记录")
    void noCompressionWhenWithinBudget() {
        List<LlmMessage> history = List.of(
                LlmMessage.system("you are helpful"),
                LlmMessage.user("hi"),
                LlmMessage.assistant("hello"));
        PreparedContext ctx = provider.prepare(history, 10_000);

        assertEquals(3, ctx.messages().size());
        assertTrue(ctx.dropped().isEmpty());
        assertFalse(ctx.compressed());
    }

    @Test
    @DisplayName("第 1 层：超长工具输出原地截断，保留头尾")
    void longToolOutputIsTruncatedNotDropped() {
        String huge = "A".repeat(20_000) + "TAIL_MARKER";
        List<LlmMessage> history = List.of(
                LlmMessage.system("sys"),
                LlmMessage.tool("t1", huge));
        PreparedContext ctx = provider.prepare(history, 100_000);

        assertEquals(2, ctx.messages().size(), "截断不应减少消息条数");
        String shortened = ctx.messages().get(1).content();
        assertTrue(shortened.length() < huge.length());
        assertTrue(shortened.contains("middle truncated"));
        assertTrue(shortened.endsWith("TAIL_MARKER"), "尾部必须保留，错误信息常在末尾");
        assertTrue(ctx.dropped().stream().anyMatch(d -> d.reason().equals("TRUNCATED")));
    }

    @Test
    @DisplayName("system 永不丢——角色与硬约束不能悄悄消失")
    void systemMessageIsNeverDropped() {
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.system("HARD_CONSTRAINT: always answer in Chinese"));
        for (int i = 0; i < 50; i++) {
            history.add(LlmMessage.user("q" + i + " " + "x".repeat(400)));
            history.add(LlmMessage.assistant("a" + i + " " + "y".repeat(400)));
        }
        PreparedContext ctx = provider.prepare(history, 800);

        assertEquals("system", ctx.messages().get(0).role());
        assertTrue(ctx.messages().get(0).content().contains("HARD_CONSTRAINT"));
        assertTrue(ctx.estimatedTokens() <= 800 * 1.1, "压完应回到预算附近");
    }

    @Test
    @DisplayName("20 轮长会话：被丢的每一块都留下记录（可归因）")
    void everythingDroppedIsRecorded() {
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.system("sys"));
        for (int i = 0; i < 20; i++) {
            history.add(LlmMessage.user("user-turn-" + i));
            history.add(LlmMessage.assistant("assistant-turn-" + i + " " + "z".repeat(600)));
        }
        PreparedContext ctx = provider.prepare(history, 500);

        assertTrue(ctx.compressed());
        assertFalse(ctx.dropped().isEmpty(), "压缩发生过就必须有 dropped 记录");
        for (PreparedContext.DroppedBlock d : ctx.dropped()) {
            assertTrue(d.estimatedTokens() >= 0);
            assertFalse(d.preview().isBlank(), "被丢的块要留下可辨识的预览，否则无法归因");
        }
        assertTrue(ctx.dropped().stream().anyMatch(d -> d.reason().equals("DROPPED_OLDEST")));
    }

    @Test
    @DisplayName("保留块逐条打分，分数与理由可解释")
    void keptBlocksAreScored() {
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.system("sys"));
        for (int i = 0; i < 10; i++) {
            history.add(LlmMessage.user("u" + i + " " + "q".repeat(300)));
        }
        PreparedContext ctx = provider.prepare(history, 600);

        assertFalse(ctx.scores().isEmpty());
        // system 分数最高，且理由是"永不丢弃"
        PreparedContext.BlockScore first = ctx.scores().get(0);
        assertEquals(1.0, first.score());
        assertTrue(first.reason().contains("never dropped"));
        // 越靠近当前的消息分数越高
        double last = ctx.scores().get(ctx.scores().size() - 1).score();
        assertTrue(last >= first.score() - 1.0);
    }
}
