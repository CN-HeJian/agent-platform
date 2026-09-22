package com.aplat.kernel;

/** 可回收订阅句柄。凡是"注册了东西"的地方都返回它，保证测试里能证明回收干净。 */
public final class Subscription {

    private volatile boolean active = true;
    private final Runnable onCancel;

    private Subscription(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public static Subscription of(Runnable onCancel) {
        return new Subscription(onCancel);
    }

    public static Subscription noop() {
        return new Subscription(() -> {
        });
    }

    public void cancel() {
        if (active) {
            active = false;
            onCancel.run();
        }
    }

    public boolean active() {
        return active;
    }
}
