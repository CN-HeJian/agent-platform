package com.aplat.workspace;

import java.util.Set;

/**
 * 工作区（U32）：一组共享的会话 + 一批成员。
 *
 * <p>为什么要"工作区"这一层，而不是直接共享会话：共享的最小单位如果是会话，
 * 权限就长在会话上，于是"谁能看这条会话"要一条一条配。工作区把这件事收成一层，
 * 于是"加一个人进这个项目"是一次操作。
 *
 * <p>它**不是**权限模型。真正的授权在 RBAC（U25）那里——工作区决定"这几条会话是一组的"，
 * 角色决定"这个人能用什么工具"。把这两件事混在一起的结果是：想让人看一条会话，
 * 就得同时给他工具权限，或者反过来。
 */
public record Workspace(
        String id,
        String name,
        Set<String> sessionIds,
        Set<String> members,
        long createdAt) {

    public Workspace {
        sessionIds = java.util.Set.copyOf(sessionIds);
        members = java.util.Set.copyOf(members);
    }

    public static Workspace create(String id, String name, String owner) {
        return new Workspace(id, name, Set.of(), Set.of(owner == null ? "" : owner),
                System.currentTimeMillis());
    }

    public Workspace withSession(String sessionId) {
        Set<String> next = new java.util.LinkedHashSet<>(sessionIds);
        next.add(sessionId);
        return new Workspace(id, name, next, members, createdAt);
    }

    public Workspace withMember(String member) {
        Set<String> next = new java.util.LinkedHashSet<>(members);
        next.add(member);
        return new Workspace(id, name, sessionIds, next, createdAt);
    }

    public boolean has(String sessionId) {
        return sessionIds.contains(sessionId);
    }
}
