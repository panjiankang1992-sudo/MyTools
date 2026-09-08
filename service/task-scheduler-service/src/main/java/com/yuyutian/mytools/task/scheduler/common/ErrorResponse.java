package com.yuyutian.mytools.task.scheduler.common;

import java.time.Instant;

/**
 * 调度服务错误响应。
 *
 * @param code 稳定错误码
 * @param message 错误摘要
 * @param timestamp 发生时间
 */
public record ErrorResponse(String code, String message, Instant timestamp) {
}
