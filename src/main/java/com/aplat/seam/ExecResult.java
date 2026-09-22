package com.aplat.seam;

import java.time.Duration;

/** 沙箱执行结果。stdout/stderr 由调用方按需截断，沙箱本身不做业务裁剪。 */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        Duration duration) {

    public boolean success() {
        return !timedOut && exitCode == 0;
    }

    public static ExecResult of(int exitCode, String stdout, String stderr, Duration d) {
        return new ExecResult(exitCode, stdout, stderr, false, d);
    }

    public static ExecResult timeout(String partialOut, Duration d) {
        return new ExecResult(-1, partialOut, "execution timed out", true, d);
    }
}
