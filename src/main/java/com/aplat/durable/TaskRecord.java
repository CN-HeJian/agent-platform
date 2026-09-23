package com.aplat.durable;

import java.util.Optional;

/**
 * 一条耐久任务的记录（U19）。
 *
 * <p>{@code step} 与 {@code attempts} 是两个不同的东西，别混：
 * <ul>
 *   <li>{@code step} —— 跑到第几步（来自最近一次检查点）。**续跑从这里接上**。</li>
 *   <li>{@code attempts} —— 被接手过几次。它回答的是"这条任务到底折腾了几回"，
 *       排查"为什么它一直在重启"时看的就是它。挂起再续不会增加它，重新接手才会。</li>
 * </ul>
 *
 * @param detail 最后一次的结果说明（失败原因、最终答复摘要），给人看的
 */
public record TaskRecord(
        String taskId,
        String sessionId,
        String goal,
        TaskState state,
        int step,
        int attempts,
        String detail,
        long createdAt,
        long updatedAt) {

    public static TaskRecord pending(String taskId, String sessionId, String goal) {
        long now = System.currentTimeMillis();
        return new TaskRecord(taskId, sessionId, goal, TaskState.PENDING, 0, 0, "", now, now);
    }

    public TaskRecord with(TaskState newState, int newStep, int newAttempts, String newDetail) {
        return new TaskRecord(taskId, sessionId, goal, newState, newStep, newAttempts,
                newDetail == null ? "" : newDetail, createdAt, System.currentTimeMillis());
    }

    public String toJson() {
        return DurableCodec.taskToJson(this);
    }

    public static Optional<TaskRecord> fromJson(String json) {
        return DurableCodec.taskFromJson(json);
    }
}
