package com.aplat.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.store.InMemoryStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U32 验收：工作区与评论。
 *
 * <p>两条最该看的：**评论挂在具体的事件上**（而不是整条会话），
 * 以及**两个工作区的评论不混**。
 */
class WorkspaceTest {

    @Test
    @DisplayName("建工作区 → 共享会话 → 在工作区里看到那条会话")
    void shareSession() {
        var ws = new WorkspaceService(new InMemoryStore());
        ws.create("w1", "周五发布", "alice");
        ws.share("w1", "s-1");
        ws.share("w1", "s-2");

        assertTrue(ws.get("w1").orElseThrow().has("s-1"));
        assertEquals(2, ws.sessionsOf("w1").size());
        assertEquals(1, ws.list().size());
    }

    @Test
    @DisplayName("评论挂在**具体的事件**上：'这里用错了工具'只有落在那一步才有意义")
    void commentOnAnEvent() {
        var ws = new WorkspaceService(new InMemoryStore());
        ws.create("w1", "周五发布", "alice");
        ws.share("w1", "s-1");
        ws.comment("w1", "s-1", 7, "bob", "这一步用了错的命令");
        ws.comment("w1", "s-1", 9, "carol", "这里应该先确认一下");

        List<Comment> comments = ws.comments("w1");
        assertEquals(2, comments.size());
        assertEquals("s-1#7", comments.get(0).where(), "要能看出评论的是第几个事件");
        assertEquals(7, comments.get(0).eventSeq());
        assertEquals("bob", comments.get(0).author());
        assertEquals(9, comments.get(1).eventSeq());
    }

    @Test
    @DisplayName("评论不能挂在没共享进来的会话上——那是把留言板挂到了不相干的地方")
    void cannotCommentOnUnsharedSession() {
        var ws = new WorkspaceService(new InMemoryStore());
        ws.create("w1", "周五发布", "alice");
        var e = assertThrows(IllegalArgumentException.class,
                () -> ws.comment("w1", "s-not-shared", 1, "bob", "什么"));
        assertTrue(e.getMessage().contains("先共享它"), e.getMessage());
    }

    @Test
    @DisplayName("两个工作区的评论互不干扰")
    void commentsAreScopedToWorkspace() {
        var ws = new WorkspaceService(new InMemoryStore());
        ws.create("w1", "A", "alice");
        ws.create("w2", "B", "bob");
        ws.share("w1", "s-1");
        ws.share("w2", "s-2");
        ws.comment("w1", "s-1", 1, "alice", "A 的评论");
        ws.comment("w2", "s-2", 1, "bob", "B 的评论");

        assertEquals(1, ws.comments("w1").size());
        assertEquals("A 的评论", ws.comments("w1").get(0).text());
        assertEquals("B 的评论", ws.comments("w2").get(0).text());
    }

    @Test
    @DisplayName("工作区视图一次给出会话、成员、评论（前端不用拼三次）")
    void viewHasEverything() {
        var ws = new WorkspaceService(new InMemoryStore());
        ws.create("w1", "周五发布", "alice");
        ws.share("w1", "s-1");
        ws.comment("w1", "s-1", 3, "bob", "注意这里");

        var view = ws.view("w1");
        assertEquals("w1", view.get("id"));
        assertEquals(1, view.get("commentCount"));
        assertTrue(((java.util.Collection<?>) view.get("sessionIds")).contains("s-1"));
        assertTrue(((List<?>) view.get("comments")).size() == 1);
    }

    @Test
    @DisplayName("空评论与不存在的工作区都要被拒（都是调用方的错，不是数据问题）")
    void validation() {
        var ws = new WorkspaceService(new InMemoryStore());
        ws.create("w1", "A", "alice");
        ws.share("w1", "s-1");
        assertThrows(IllegalArgumentException.class,
                () -> ws.comment("w1", "s-1", 1, "bob", "   "));
        assertThrows(IllegalArgumentException.class, () -> ws.view("nope"));
        assertThrows(IllegalArgumentException.class, () -> ws.share("nope", "s-1"));
    }
}
