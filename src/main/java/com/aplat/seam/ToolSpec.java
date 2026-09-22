package com.aplat.seam;

/** 工具的对外声明：名字、说明、参数 JSON Schema。 */
public record ToolSpec(String name, String description, String parametersJson) {

    public static ToolSpec of(String name, String description, String parametersJson) {
        return new ToolSpec(name, description, parametersJson);
    }
}
