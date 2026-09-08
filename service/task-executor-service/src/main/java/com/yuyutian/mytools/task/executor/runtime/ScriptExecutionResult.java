package com.yuyutian.mytools.task.executor.runtime;

import java.time.Duration;

/**
 * 脚本执行结果。
 *
 * @param exitCode 退出码
 * @param standardOutput 标准输出
 * @param standardError 标准错误
 * @param duration 执行时长
 * @param timedOut 是否超时
 * @param cancelled 是否取消
 * @param logIndex 日志分段索引
 */
public record ScriptExecutionResult(
        int exitCode,
        String standardOutput,
        String standardError,
        Duration duration,
        boolean timedOut,
        boolean cancelled,
        ProcessLogIndex logIndex
) {
    /**
     * 创建不带日志索引的兼容结果。
     */
    public ScriptExecutionResult(int exitCode, String standardOutput, String standardError, Duration duration,
                                 boolean timedOut, boolean cancelled) {
        this(exitCode, standardOutput, standardError, duration, timedOut, cancelled, ProcessLogIndex.empty());
    }
}
