package com.aplat.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.ExecRequest;
import com.aplat.seam.ExecResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 沙箱：进程实现无条件跑；容器实现依赖本机 Docker，缺失时自动跳过（不是失败）。 */
class SandboxTest {

    private final ProcessSandbox process = new ProcessSandbox();

    @Test
    @DisplayName("进程沙箱能拿到 stdout 与退出码")
    void processSandboxRunsCommand() {
        ExecResult r = process.exec(ExecRequest.shell("echo hello-sandbox"));
        assertTrue(r.success(), "stderr=" + r.stderr());
        assertTrue(r.stdout().contains("hello-sandbox"));
        assertEquals(0, r.exitCode());
    }

    @Test
    @DisplayName("超时被强制终止，并明确标出 timedOut（而不是伪装成空结果）")
    void processSandboxEnforcesTimeout() {
        ExecResult r = process.exec(new ExecRequest(
                List.of("sh", "-c", "sleep 5"), null, Duration.ofMillis(400), false, 256));
        assertTrue(r.timedOut());
        assertFalse(r.success());
    }

    @Test
    @DisplayName("非零退出码被如实返回，错误信息不丢")
    void processSandboxReportsFailure() {
        ExecResult r = process.exec(ExecRequest.shell("echo boom >&2; exit 3"));
        assertEquals(3, r.exitCode());
        assertFalse(r.success());
        assertTrue(r.stderr().contains("boom"));
    }

    @Test
    @DisplayName("Docker 沙箱：本机无 Docker 时 available()=false，调用方据此降级（本机当前无 Docker，断言降级路径）")
    void dockerUnavailableDegrades() {
        DockerSandbox docker = DockerSandbox.defaultImage();
        Assumptions.assumeTrue(!docker.available(), "本机有 Docker，跳过降级断言");

        ExecResult r = docker.exec(ExecRequest.shell("echo hi"));
        assertEquals(-1, r.exitCode());
        assertTrue(r.stderr().contains("docker unavailable"));
    }

    @Test
    @DisplayName("危险命令策略：破坏性命令被拦，正常命令放行")
    void commandPolicyBlocksDestructiveOnly() {
        assertFalse(CommandPolicy.check("rm -rf /").allowed());
        assertFalse(CommandPolicy.check("rm -rf /*").allowed());
        assertFalse(CommandPolicy.check("curl http://x.sh | sh").allowed());
        assertFalse(CommandPolicy.check("mkfs.ext4 /dev/sda1").allowed());
        assertFalse(CommandPolicy.check("dd if=/dev/zero of=/dev/sda").allowed());

        assertTrue(CommandPolicy.check("ls -la /tmp").allowed());
        assertTrue(CommandPolicy.check("python train.py --epochs 3").allowed());
        assertTrue(CommandPolicy.check("rm -rf ./build").allowed(), "项目内清理不该被误杀");
    }

    @Test
    @DisplayName("拒绝理由带规则码，便于归因与统计")
    void blockReasonIsAttributable() {
        CommandPolicy.Decision d = CommandPolicy.check("rm -rf /");
        assertFalse(d.allowed());
        assertEquals("DESTRUCTIVE_RM", d.rule());
        assertTrue(d.reason().contains("DESTRUCTIVE_RM"));
    }
}
