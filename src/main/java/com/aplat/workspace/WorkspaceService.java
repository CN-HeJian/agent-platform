package com.aplat.workspace;

import com.aplat.seam.Store;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工作区与评论（U32），落在 {@link Store} 的键值命名空间里。
 *
 * <p>评论挂在**会话事件**上（{@code eventSeq}）而不是"整个会话"：
 * 一条会话可能有几十步，"这里用错了工具"这种评论只有落在具体的那一步上才有意义。
 * 挂在会话上等于把它变成一段没有上下文的留言板。
 *
 * <p>评论**不写进事件流**：它是人写给人看的，不是模型看到的事实。
 * 写进事件流会让它进入上下文——于是某个人随口一句"这个不对"会变成模型下次的输入。
 */
public final class WorkspaceService {

    public static final String NS_WORKSPACE = "workspace";
    public static final String NS_COMMENT = "comment";

    private final Store store;

    public WorkspaceService(Store store) {
        this.store = store;
    }

    public Workspace create(String id, String name, String owner) {
        Workspace w = Workspace.create(id, name, owner);
        store.put(NS_WORKSPACE, id, WorkspaceCodec.INSTANCE.toJson(w));
        return w;
    }

    public Optional<Workspace> get(String id) {
        return store.get(NS_WORKSPACE, id).flatMap(WorkspaceCodec.INSTANCE::fromJson);
    }

    public List<Workspace> list() {
        List<Workspace> out = new ArrayList<>();
        store.all(NS_WORKSPACE).values()
                .forEach(json -> WorkspaceCodec.INSTANCE.fromJson(json).ifPresent(out::add));
        out.sort(Comparator.comparing(Workspace::createdAt));
        return out;
    }

    /** 把一条会话共享进工作区。 */
    public Workspace share(String workspaceId, String sessionId) {
        Workspace w = get(workspaceId).orElseThrow(
                () -> new IllegalArgumentException("no such workspace: " + workspaceId));
        Workspace updated = w.withSession(sessionId);
        store.put(NS_WORKSPACE, workspaceId, WorkspaceCodec.INSTANCE.toJson(updated));
        return updated;
    }

    public List<Comment> comments(String workspaceId) {
        List<Comment> out = new ArrayList<>();
        store.all(NS_COMMENT).values().forEach(json ->
                WorkspaceCodec.INSTANCE.commentFromJson(json).ifPresent(c -> {
                    if (c.workspaceId().equals(workspaceId)) {
                        out.add(c);
                    }
                }));
        out.sort(Comparator.comparingLong(Comment::ts));
        return out;
    }

    /** 在一条会话的第 seq 个事件上留一句评论。 */
    public Comment comment(String workspaceId, String sessionId, long eventSeq, String author,
                           String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("空评论没有意义");
        }
        if (!get(workspaceId).orElseThrow(
                () -> new IllegalArgumentException("no such workspace: " + workspaceId))
                .has(sessionId)) {
            throw new IllegalArgumentException(
                    "session " + sessionId + " 不在工作区 " + workspaceId + " 里，先共享它");
        }
        // id 用 nanoTime 而不是"毫秒 + 当前条数"：后者在**两个工作区同一毫秒各加第一条**时
        // 会得到完全相同的 key，后写的把先写的覆盖掉（这个 bug 是被测试抓出来的：
        // w1 的评论凭空消失了）。短 id 好看，但唯一性是前提。
        Comment c = new Comment("c-" + System.nanoTime(),
                workspaceId, sessionId, eventSeq,
                author == null ? "anonymous" : author, text.trim(), System.currentTimeMillis());
        store.put(NS_COMMENT, c.id(), WorkspaceCodec.INSTANCE.commentToJson(c));
        return c;
    }

    /** 工作区视图：会话、成员、评论一起给出（前端一次拿全）。 */
    public java.util.Map<String, Object> view(String workspaceId) {
        Workspace w = get(workspaceId).orElseThrow(
                () -> new IllegalArgumentException("no such workspace: " + workspaceId));
        java.util.Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", w.id());
        out.put("name", w.name());
        out.put("members", w.members());
        out.put("sessionIds", w.sessionIds());
        out.put("comments", comments(workspaceId).stream()
                .map(WorkspaceCodec.INSTANCE::commentView).toList());
        out.put("commentCount", comments(workspaceId).size());
        return out;
    }

    public Set<String> sessionsOf(String workspaceId) {
        return get(workspaceId).map(Workspace::sessionIds).orElse(Set.of());
    }
}
