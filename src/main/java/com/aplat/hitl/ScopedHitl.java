package com.aplat.hitl;

import com.aplat.seam.Hitl;
import com.aplat.seam.HitlDecision;
import com.aplat.seam.HitlRequest;
import com.aplat.seam.SessionLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 带"提前批准"作用域的人机确认（U22）。
 *
 * <p>解决的问题只有一个：定时任务在凌晨两点跑，而"先问人"在那个时刻等价于"等到超时"。
 *
 * <p>做法是给 {@link Hitl} 套一层**按线程生效**的豁免名单：调度器在跑某一条调度之前
 * 把它的 {@code preApprovedTools} 放进作用域，这条线程上的调用命中名单就直接放行，
 * 其余一切照旧（包括不在名单上的工具，以及别的会话、别的人发起的调用）。
 *
 * <h2>为什么用线程作用域而不是"改 Hitl 的全局状态"</h2>
 *
 * <p>全局状态会让豁免泄漏：调度跑完之后名单还留着，下一个人在会话里调同一个工具就被静默放行了。
 * 线程作用域天然只在"跑这一条调度的这段代码"里有效，作用域退出即失效——
 * 而这个边界正好和"谁批准的"这个问题对齐。
 *
 * <h2>但要承认它的边界</h2>
 *
 * <p>线程作用域依赖"整条执行链都在这一个线程上"。如果将来某个工具内部又开了线程池去
 * 执行子调用，那份豁免不会跟过去——那是对的，但要说出来：**豁免不继承到子线程**。
 * 反过来（把豁免做成 {@code InheritableThreadLocal}）更危险：它会静默地扩大到
 * 一整棵线程树，而"我批的到底是哪一次"就说不清了。
 *
 * <p>放行仍然留痕：记一条 {@code hitl.pre_approved}，写清是哪条调度批的。
 * 少了这条，"凌晨两点跑了一个命令"和"凌晨两点有人批了一个命令"在日志里会长得一样。
 */
public final class ScopedHitl implements Hitl {

    private static final ThreadLocal<Source> SCOPE = new ThreadLocal<>();

    private final Hitl delegate;
    private final SessionLog log;

    public ScopedHitl(Hitl delegate, SessionLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    /**
     * 在**当前线程**上开启豁免作用域。用 try-with-resources 使用，退出即失效。
     *
     * @param tools           免确认的工具名
     * @param attribution     谁批的（写进事件里，例如 {@code scheduleId}）
     */
    public static Scope scope(List<String> tools, String attribution) {
        Source previous = SCOPE.get();
        SCOPE.set(new Source(Set.copyOf(tools), attribution));
        return () -> {
            if (previous == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previous);
            }
        };
    }

    /** 便捷重载：批注用调度 id。 */
    public static Scope scope(List<String> tools) {
        return scope(tools, "scheduled");
    }

    @Override
    public String id() {
        return "hitl.scoped(" + delegate.id() + ")";
    }

    public Hitl delegate() {
        return delegate;
    }

    /**
     * 剥掉所有作用域包装，拿到真正的实现。
     *
     * <p>为什么需要它：这一层是**透明的**——它不改变 Hitl 的任何语义，只是加了个豁免名单。
     * 但"谁在等人"这类问题必须问到具体实现（只有 {@code InteractiveHitl} 有 pending 队列），
     * 而包装之后 {@code instanceof InteractiveHitl} 就不成立了。
     *
     * <p>处理方式是显式拆包，而不是让上层"知道有个 ScopedHitl 存在"：
     * 将来再加一层包装（比如"按租户限流的 Hitl"）只要也继承同一个约定，
     * 这里一处都不用改。
     */
    public static Hitl unwrap(Hitl hitl) {
        Hitl h = hitl;
        while (h instanceof ScopedHitl scoped) {
            h = scoped.delegate();
        }
        return h;
    }

    @Override
    public HitlDecision request(HitlRequest request) {
        Source source = SCOPE.get();
        if (source == null || !source.tools().contains(request.toolName())) {
            return delegate.request(request);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", request.toolName());
        payload.put("approvedBy", source.attribution());
        payload.put("args", request.argumentsJson());
        log.append(request.sessionId(), SessionLog.EV_HITL_PREAPPROVED, payload);
        return new HitlDecision.Once();
    }

    /** 可关闭的作用域。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record Source(Set<String> tools, String attribution) {
    }
}
