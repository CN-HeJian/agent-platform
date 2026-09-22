package com.aplat.sandbox;

import com.aplat.seam.ExecRequest;
import com.aplat.seam.ExecResult;
import com.aplat.seam.Sandbox;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 进程级沙箱：开发兜底实现。
 *
 * <p>**它不是安全边界**（与宿主同权限），只用来在没有 Docker 的环境里把链路跑通。
 * 生产必须换 {@link DockerSandbox}。
 */
public final class ProcessSandbox implements Sandbox {

    @Override
    public String id() {
        return "sandbox.process";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExecResult exec(ExecRequest request) {
        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(request.command());
        if (request.workdir() != null) {
            pb.directory(new java.io.File(request.workdir()));
        }
        pb.redirectErrorStream(false);

        Process process = null;
        try {
            process = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Process finalProcess = process;
            Thread outReader = drain(finalProcess.getInputStream(), out);
            Thread errReader = drain(finalProcess.getErrorStream(), err);

            boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outReader.join(500);
                errReader.join(500);
                return ExecResult.timeout(out.toString(), Duration.between(start, Instant.now()));
            }
            outReader.join(1000);
            errReader.join(1000);
            return ExecResult.of(process.exitValue(), out.toString(), err.toString(),
                    Duration.between(start, Instant.now()));
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecResult(-1, "", "process sandbox failure: " + e.getMessage(), false,
                    Duration.between(start, Instant.now()));
        }
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // 进程被杀时读流中断属正常
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
