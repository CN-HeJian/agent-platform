package com.aplat.seam;

import java.time.Duration;
import java.util.List;

/** 一次沙箱执行请求。 */
public record ExecRequest(
        List<String> command,
        String workdir,
        Duration timeout,
        boolean networkEnabled,
        long memoryLimitMb) {

    public ExecRequest {
        command = List.copyOf(command);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    public static ExecRequest of(List<String> command) {
        return new ExecRequest(command, null, Duration.ofSeconds(30), false, 512);
    }

    public static ExecRequest shell(String script) {
        return new ExecRequest(List.of("sh", "-c", script), null, Duration.ofSeconds(30), false, 512);
    }
}
