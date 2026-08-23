package com.yuyutian.mytools.task.executor.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 脚本执行请求。
 *
 * @param command 命令及参数列表
 * @param workingDirectory 工作目录
 * @param environment 允许注入的环境变量
 * @param timeout 超时时间
 * @param cancellationRequested 取消状态读取器
 */
public record ScriptExecutionRequest(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        BooleanSupplier cancellationRequested
) {

    /**
     * 创建不支持外部取消的脚本请求。
     *
     * @param command 命令及参数列表
     * @param workingDirectory 工作目录
     * @param environment 环境变量
     * @param timeout 超时时间
     */
    public ScriptExecutionRequest(List<String> command, Path workingDirectory,
                                  Map<String, String> environment, Duration timeout) {
        this(command, workingDirectory, environment, timeout, () -> false);
    }
}
