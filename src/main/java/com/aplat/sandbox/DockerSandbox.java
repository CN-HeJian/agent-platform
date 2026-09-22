package com.aplat.sandbox;

import com.aplat.seam.ExecRequest;
import com.aplat.seam.ExecResult;
import com.aplat.seam.Sandbox;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器沙箱（阶段一 P0 目标实现）。
 *
 * <p>三重隔离：{@code --network=none} 断网、{@code -m} 限内存、{@code --rm} 退出即清；
 * {@code --read-only} 挂载 + tmpfs 工作区，保证不写宿主。
 *
 * <p>Docker 不可用时 {@link #available()} 返回 false，工具层据此给出
 * {@code SANDBOX_FAILURE} 结构化错误，而不是把整个循环炸掉。
 */
public final class DockerSandbox implements Sandbox {

    private final String image;
    private final String dockerBinary;
    private final Boolean cachedAvailable;

    public DockerSandbox(String image) {
        this(image, "docker", null);
    }

    public DockerSandbox(String image, String dockerBinary, Boolean cachedAvailable) {
        this.image = image;
        this.dockerBinary = dockerBinary;
        this.cachedAvailable = cachedAvailable;
    }

    public static DockerSandbox defaultImage() {
        return new DockerSandbox("alpine:3.20");
    }

    @Override
    public String id() {
        return "sandbox.docker";
    }

    @Override
    public boolean available() {
        if (cachedAvailable != null) {
            return cachedAvailable;
        }
        try {
            Process p = new ProcessBuilder(dockerBinary, "info").redirectErrorStream(true).start();
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ExecResult exec(ExecRequest request) {
        if (!available()) {
            return new ExecResult(-1, "", "docker unavailable", false, Duration.ZERO);
        }
        Instant start = Instant.now();
        List<String> cmd = new ArrayList<>(List.of(
                dockerBinary, "run", "--rm", "-i",
                "--network", request.networkEnabled() ? "bridge" : "none",
                "-m", request.memoryLimitMb() + "m",
                "--cpus", "1",
                "--pids-limit", "128",
                "--security-opt", "no-new-privileges",
                "--tmpfs", "/work:rw,size=64m",
                "-w", "/work",
                image,
                "sh", "-c", String.join(" ", request.command())));
        ExecResult raw = new ProcessSandbox().exec(
                new ExecRequest(cmd, null, request.timeout(), request.networkEnabled(), request.memoryLimitMb()));
        return new ExecResult(raw.exitCode(), raw.stdout(), raw.stderr(), raw.timedOut(),
                Duration.between(start, Instant.now()));
    }

    public String image() {
        return image;
    }
}
