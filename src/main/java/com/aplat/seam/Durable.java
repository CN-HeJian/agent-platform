package com.aplat.seam;

import java.util.Optional;

/**
 * 耐久执行缝（阶段二）。内部先用自研状态机实现，外部引擎（如 Temporal）日后可换成另一实现，
 * 因为消费方只认这个接口。
 */
public interface Durable extends Seam {

    String start(String sessionId, String goal);

    Optional<ResumePoint> resumePoint(String sessionId);

    /** 步骤级检查点：崩溃后从 recentCheckpoint 之后的事件续跑。 */
    record ResumePoint(String runId, int stepIndex, String stateJson) {
    }
}
