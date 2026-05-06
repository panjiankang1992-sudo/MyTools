package com.yuyutian.mytools.wechat.moments.model;

import lombok.Getter;

/**
 * 操作类型枚举。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Getter
public enum OperateType {

    REFRESH_TASK_LIST(0, "刷新任务列表"),
    PUBLISH_MOMENTS(1, "发布朋友圈"),
    MANUAL(2, "手动刷新"),
    AUTOMATIC(3, "自动刷新");

    private final Integer code;
    private final String description;

    OperateType(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
