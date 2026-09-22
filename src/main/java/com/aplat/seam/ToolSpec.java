package com.aplat.seam;

/**
 * 工具的对外声明：名字、说明、参数 JSON Schema，以及**哪个参数是命令/脚本**。
 *
 * <p>{@code commandField} 是策略层识别"执行类工具"的**唯一凭据**。原来的做法是在管线里
 * 判断 {@code "shell".equals(call.name())}——只要你新加一个会执行命令的工具就漏过去了。
 * 所以规则是：
 *
 * <blockquote>
 * 凡是会把某个参数<b>当命令或脚本执行</b>的工具，必须用 {@link #executing} 声明字段名。
 * 没声明，就不要指望它被拦截。
 * </blockquote>
 *
 * <p>漏声明不会静默漏过——{@code DefaultToolPolicy.lint()} 会在装配期把"名字像执行类
 * 却没声明字段"的工具报出来。
 *
 * @param commandField 承载命令/脚本的参数名（如 {@code "command"} / {@code "script"}）；null = 纯计算类
 */
public record ToolSpec(String name, String description, String parametersJson, String commandField) {

    /** 纯计算类工具的便捷构造（不执行任何命令）。 */
    public ToolSpec(String name, String description, String parametersJson) {
        this(name, description, parametersJson, null);
    }

    public static ToolSpec of(String name, String description, String parametersJson) {
        return new ToolSpec(name, description, parametersJson, null);
    }

    /**
     * 执行类工具**必须**用这个工厂。
     *
     * @param commandField 哪个参数会被当作命令/脚本执行，例如 {@code "command"}
     */
    public static ToolSpec executing(String name, String description, String parametersJson, String commandField) {
        if (commandField == null || commandField.isBlank()) {
            throw new IllegalArgumentException(
                    "executing tool must declare which argument holds the command: " + name);
        }
        return new ToolSpec(name, description, parametersJson, commandField);
    }

    /** 是否属于"会执行命令/脚本"的工具——决定策略层要不要做危险命令检查。 */
    public boolean executesCommands() {
        return commandField != null;
    }
}
