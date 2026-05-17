package com.yuyutian.mytools.appmarket.enums;

/**
 * 应用状态枚举。
 *
 * @author mytools
 * @since 2026-05-16
 */
public enum AppStatus {
    /** 已发布 */
    PUBLISHED("PUBLISHED"),
    /** 草稿 */
    DRAFT("DRAFT");

    private final String value;

    AppStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
