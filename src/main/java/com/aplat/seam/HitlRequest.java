package com.aplat.seam;

import java.time.Duration;

/**
 * 一次人机确认请求。
 *
 * @param timeout 本次请求的等待上限；<b>null = 用实现自己的默认值</b>。
 *                刻意不在这里兜一个默认值——"等多久"是部署策略（本地调试想等 10 秒、
 *                线上可以等 5 分钟），只有读得到配置的实现才该决定，DTO 替它做主
 *                只会让配置永远生效不了。<b>零或负数</b>是有意义的：表示"不等待"——
 *                批处理与测试会这么用（直接走超时路径，不留下待办）。
 */
public record HitlRequest(
        String sessionId,
        String toolName,
        String argumentsJson,
        String reason,
        Duration timeout) {
}
