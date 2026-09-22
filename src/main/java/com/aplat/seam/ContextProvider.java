package com.aplat.seam;

import java.util.List;

/**
 * 上下文缝：预算裁剪 + 分层压缩。
 *
 * <p>硬约束：**不许静默丢东西**。每一块为什么留、为什么丢都要能查——否则线上"模型忘了
 * 早期约束"这类问题无从定位。
 */
public interface ContextProvider extends Seam {

    PreparedContext prepare(List<LlmMessage> history, int budgetTokens);
}
