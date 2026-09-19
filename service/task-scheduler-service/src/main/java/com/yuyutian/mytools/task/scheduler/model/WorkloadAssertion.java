package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;

/** 仅驻留传输内存的短期凭据，不可保存到数据库、任务参数或执行报告。 */
public record WorkloadAssertion(String token, Instant expiresAt) {
    /** 默认诊断不包含 bearer 值。 */
    @Override
    public String toString() {
        return "WorkloadAssertion[redacted]";
    }
}
