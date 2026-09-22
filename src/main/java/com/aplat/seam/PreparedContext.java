package com.aplat.seam;

import java.util.List;

/**
 * 送进模型之前的上下文。
 *
 * @param messages       裁剪/压缩后的消息
 * @param dropped        被丢掉的块（双记录之一：可归因）
 * @param scores         每个保留块的保留分数（双记录之二：可解释）
 * @param estimatedTokens 估算后的 token 数
 * @param compressed     是否发生过压缩
 */
public record PreparedContext(
        List<LlmMessage> messages,
        List<DroppedBlock> dropped,
        List<BlockScore> scores,
        int estimatedTokens,
        boolean compressed) {

    public PreparedContext {
        messages = List.copyOf(messages);
        dropped = List.copyOf(dropped);
        scores = List.copyOf(scores);
    }

    public record DroppedBlock(String reason, int index, int estimatedTokens, String preview) {
    }

    public record BlockScore(int index, double score, String reason) {
    }
}
