package com.aplat.seam;

import java.time.Duration;

/** 一次人机确认请求。 */
public record HitlRequest(
        String sessionId,
        String toolName,
        String argumentsJson,
        String reason,
        Duration timeout) {

    public HitlRequest {
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }
}
