package com.aplat.tools;

import com.aplat.seam.ToolSpec;

/**
 * 工具策略：模型"想调用"到"真的执行"之间的**唯一裁决口**。
 *
 * <p>它取代了原先"在管线里判断工具名等不等于 shell"的做法。那种写法的洞很明确：
 * 判断条件是**名字**，所以任何新加的执行类工具都自动绕过检查——而"新加执行类工具"
 * 恰恰是这套系统最常见的扩展动作。
 *
 * <p>现在的判定依据是声明（{@link ToolSpec#commandField()}）而不是名字，
 * 并且**每次调用都会被问到**——漏检查不再可能，只可能"声明漏了"，
 * 而声明漏了会在装配期被 lint 报出来（见 {@link DefaultToolPolicy#lint}）。
 *
 * <p>按设计基线它属于"普通模块"（不进服务台）：判定是纯函数，没有外部依赖。
 * U25 接 RBAC 时换一个实现对象即可，等价于换一条缝。
 */
public interface ToolPolicy {

    /**
     * @param sessionId     会话 id（将来按会话/用户授权时用得上）
     * @param spec          工具声明——判定依据看这里，不看名字
     * @param argumentsJson 模型给的参数（危险命令检查要从里面取命令）
     */
    Decision check(String sessionId, ToolSpec spec, String argumentsJson);

    record Decision(boolean allowed, String code, String reason) {

        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision deny(String code, String reason) {
            return new Decision(false, code, reason);
        }
    }

    /**
     * 组合两个策略：**任一拒绝就拒绝**（先问这个，过了再问那个）。
     *
     * <p>存在的理由：RBAC（"这个人能不能用这个工具"）与危险命令检查（"这条命令能不能跑"）
     * 是两件事，各管一半。合成一个策略类会让两者的测试互相纠缠，而组合子让它们各自独立可测。
     *
     * <p>短路在**第一个拒绝**处停下：第二个策略只在第一个放行时才被问到，
     * 于是"没有身份"这种情况不会被危险命令检查的日志刷一遍。
     */
    default ToolPolicy and(ToolPolicy other) {
        return (sessionId, spec, argumentsJson) -> {
            Decision first = check(sessionId, spec, argumentsJson);
            return first.allowed() ? other.check(sessionId, spec, argumentsJson) : first;
        };
    }

    /** 只用于测试与"确实不需要策略"的场景；生产装配默认用 {@link DefaultToolPolicy}。 */
    static ToolPolicy allowAll() {
        return (sessionId, spec, argumentsJson) -> Decision.allow();
    }
}
