package com.aplat.seam;

/**
 * 人机协同缝。门控点：命中 {@link Tool#approvalRequired} 的工具调用之前。
 *
 * <p>实现可以是"自动放行"（开发）、"控制台问答"（本地）、"SSE 推给前端等回包"（线上），
 * 上层循环不需要知道是哪种。
 */
public interface Hitl extends Seam {

    HitlDecision request(HitlRequest request);

    /** 开发/测试用的自动放行实现。 */
    static Hitl autoAllow() {
        return new Hitl() {
            @Override
            public String id() {
                return "hitl.auto-allow";
            }

            @Override
            public HitlDecision request(HitlRequest request) {
                return new HitlDecision.Once();
            }
        };
    }

    /** 开发/测试用的自动拒绝实现。 */
    static Hitl autoDeny(String reason) {
        return new Hitl() {
            @Override
            public String id() {
                return "hitl.auto-deny";
            }

            @Override
            public HitlDecision request(HitlRequest request) {
                return new HitlDecision.Deny(reason);
            }
        };
    }
}
