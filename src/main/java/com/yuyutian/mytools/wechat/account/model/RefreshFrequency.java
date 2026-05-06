package com.yuyutian.mytools.wechat.account.model;

import lombok.Getter;

/**
 * 刷新频率枚举。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Getter
public enum RefreshFrequency {

    ONCE_DAILY(1, "每天一次"),
    TWICE_DAILY(2, "每天两次");

    private final Integer code;
    private final String description;

    RefreshFrequency(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
