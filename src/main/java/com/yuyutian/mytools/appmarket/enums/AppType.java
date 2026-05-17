package com.yuyutian.mytools.appmarket.enums;

/**
 * 应用类型枚举。
 *
 * @author mytools
 * @since 2026-05-16
 */
public enum AppType {
    /** 富文本HTML内容 */
    APP("app"),
    /** 可执行二进制文件 */
    CLI("cli"),
    /** JSON配置文件 */
    MCP("mcp"),
    /** ZIP压缩包 */
    SKILL("skill");

    private final String value;

    AppType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AppType fromValue(String value) {
        for (AppType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown app type: " + value);
    }
}
