package com.aplat.seam;

/**
 * 执行环境缝。这是安全边界所在：所有"模型想跑的命令"都必须经这里，
 * 不允许任何工具绕过它直接 ProcessBuilder。
 *
 * <p>分级：进程级（开发兜底） → 容器（阶段一，Docker） → 微 VM / K8s（阶段三）。
 */
public interface Sandbox extends Seam {

    ExecResult exec(ExecRequest request);

    /** 当前环境是否可用。不可用时依赖它的工具有序降级，而不是抛异常拖垮主链。 */
    boolean available();

    default String description() {
        return id() + (available() ? " [available]" : " [unavailable]");
    }
}
