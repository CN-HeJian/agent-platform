package com.aplat.loop;

/**
 * 循环预算。三个数各自防一类失控：步数防"想太久"、上下文防"记太多"、超时防"卡住"。
 */
public record LoopBudget(int maxSteps, int contextBudgetTokens) {

    public static LoopBudget defaults() {
        return new LoopBudget(8, 8_000);
    }

    public LoopBudget {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }
}
