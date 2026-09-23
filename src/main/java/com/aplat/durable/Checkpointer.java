package com.aplat.durable;

import com.aplat.seam.LlmMessage;
import java.util.List;
import java.util.Optional;

/**
 * 步骤级检查点缝（U20）。
 *
 * <p>每一次 step 的末尾把**完整的消息列表**存下来。为什么要存消息而不是"第几步"：
 * 续跑时模型需要看到与崩溃前**一模一样**的上下文——包括工具返回的观察。
 * 只记一个步号，续跑就等于让模型失忆重来，那还不如不恢复。
 *
 * <p>按 {@code taskId} 而不是 {@code sessionId} 索引：同一个会话可以先后跑多个任务，
 * 用会话做键会让新任务读到旧任务的检查点——一个非常隐蔽的错。
 */
public interface Checkpointer {

    void checkpoint(String taskId, int step, List<LlmMessage> messages);

    Optional<Checkpoint> latest(String taskId);

    /** 清掉检查点（新任务起跑前调用，避免读到上一个任务的残留）。 */
    void clear(String taskId);

    /** 不落盘的实现：给不需要恢复的场景（同步单次调用、单测）用。 */
    static Checkpointer noop() {
        return new Checkpointer() {
            @Override
            public void checkpoint(String taskId, int step, List<LlmMessage> messages) {
            }

            @Override
            public Optional<Checkpoint> latest(String taskId) {
                return Optional.empty();
            }

            @Override
            public void clear(String taskId) {
            }

            @Override
            public String toString() {
                return "checkpointer.noop";
            }
        };
    }

    /** 一个检查点：跑到第几步、当时上下文里有什么。 */
    record Checkpoint(int step, List<LlmMessage> messages) {
        public Checkpoint {
            messages = List.copyOf(messages);
        }
    }
}
