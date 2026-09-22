package com.aplat.seam;

/**
 * 人机确认结果。四种决策各自对应不同后果，别把它们压成一个 boolean——
 * "always" 影响的是后续所有同类调用，"deny" 要走改道而不是重试。
 */
public sealed interface HitlDecision {

    /** 本次放行。 */
    record Once() implements HitlDecision {
    }

    /** 本会话内同类调用一律放行（需落审计）。 */
    record Always() implements HitlDecision {
    }

    /** 拒绝执行，循环把拒绝当成观察回填给模型，让它换路子。 */
    record Deny(String reason) implements HitlDecision {
    }

    /** 用户改了参数再执行。 */
    record Modified(String newArgumentsJson) implements HitlDecision {
    }

    /** 超时未响应：按拒绝处理，但错误码不同（便于区分"人不在"与"人反对"）。 */
    record Timeout() implements HitlDecision {
    }
}
