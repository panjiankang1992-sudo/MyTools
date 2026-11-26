package com.yuyutian.mytools.toolbox.service.events;

import java.util.Arrays;
import java.util.Optional;

/**
 * 公开客户端允许上报的事件类型。
 * 不存在 pdf_success：PDF 成功由服务端任务终态产生，不接受客户端伪造。
 */
public enum EventType {

    /** 一级目录入口点击。 */
    ENTRY_CLICK("entry_click"),
    /** 题单成功使用。 */
    WORKSHEET_SUCCESS("worksheet_success"),
    /** 发起系统打印。 */
    PRINT_REQUEST("print_request");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    /**
     * 返回契约中的序列化值。
     *
     * @return 事件类型值
     */
    public String value() {
        return value;
    }

    /**
     * 按序列化值解析事件类型。
     *
     * @param value 事件类型字符串
     * @return 事件类型或空
     */
    public static Optional<EventType> parse(String value) {
        return Arrays.stream(values()).filter(item -> item.value.equals(value)).findFirst();
    }
}
