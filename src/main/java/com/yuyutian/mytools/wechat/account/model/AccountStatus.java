package com.yuyutian.mytools.wechat.account.model;

/**
 * 账号状态枚举
 */
public enum AccountStatus {
    NORMAL(1, "正常"),
    DISABLED(2, "禁用");

    private final Integer code;
    private final String description;

    AccountStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}