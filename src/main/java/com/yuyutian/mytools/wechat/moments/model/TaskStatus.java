package com.yuyutian.mytools.wechat.moments.model;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    PENDING(1, "待启动"),
    RUNNING(2, "执行中"),
    SUCCESS(3, "成功"),
    FAILED(4, "失败"),
    DELETED(5, "已删除");

    private final Integer code;
    private final String description;

    TaskStatus(Integer code, String description) {
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
