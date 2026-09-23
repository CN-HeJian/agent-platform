package com.aplat.durable;

import com.aplat.seam.LlmMessage;
import com.aplat.seam.Store;
import java.util.List;
import java.util.Optional;

/**
 * 落在 {@link Store} 上的检查点（U20）。
 *
 * <p>用命名空间键值而不是 {@code Store.Snapshot}：{@code Snapshot} 是**每条会话一行**，
 * 而一个会话可能先后跑多个任务（尤其有调度之后）。用会话做键会让任务 B 读到任务 A 的检查点，
 * 而那种错在日志里表现为"模型突然开始说另一件事"，极难定位。
 */
public final class StoreCheckpointer implements Checkpointer {

    /** 命名空间。所有耐久层的键值都集中在这里登记，避免各模块随手起名。 */
    public static final String NS = "checkpoint";

    private final Store store;

    public StoreCheckpointer(Store store) {
        this.store = store;
    }

    @Override
    public void checkpoint(String taskId, int step, List<LlmMessage> messages) {
        store.put(NS, taskId, DurableCodec.messagesToJson(step, messages));
    }

    @Override
    public Optional<Checkpoint> latest(String taskId) {
        return store.get(NS, taskId).flatMap(DurableCodec::checkpointFromJson);
    }

    @Override
    public void clear(String taskId) {
        store.remove(NS, taskId);
    }
}
