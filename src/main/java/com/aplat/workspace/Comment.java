package com.aplat.workspace;

/**
 * 一条评论，挂在**会话的某个事件**上（U32）。
 *
 * @param eventSeq 评论的是第几个事件；{@code 0} = 针对整条会话
 */
public record Comment(
        String id,
        String workspaceId,
        String sessionId,
        long eventSeq,
        String author,
        String text,
        long ts) {

    public String where() {
        return eventSeq == 0 ? sessionId : sessionId + "#" + eventSeq;
    }
}
