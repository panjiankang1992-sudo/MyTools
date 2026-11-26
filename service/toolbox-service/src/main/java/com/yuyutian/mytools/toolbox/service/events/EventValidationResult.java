package com.yuyutian.mytools.toolbox.service.events;

/**
 * 单条事件校验结果：要么得到可写入的事件，要么得到稳定拒绝原因。
 */
public sealed interface EventValidationResult {

    /**
     * 校验通过。
     *
     * @param event 已校验事件
     */
    record Valid(ValidatedEvent event) implements EventValidationResult {
    }

    /**
     * 校验失败。
     *
     * @param reason 稳定拒绝原因
     */
    record Rejected(EventRejectReason reason) implements EventValidationResult {
    }
}
