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
 */
public record ScriptExecutionResult(
        int exitCode,
        String standardOutput,
        String standardError,
        Duration duration,
        boolean timedOut
) {
}
