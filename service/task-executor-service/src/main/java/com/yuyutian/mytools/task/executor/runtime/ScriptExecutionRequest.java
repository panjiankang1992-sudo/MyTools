package com.yuyutian.mytools.task.executor.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 脚本执行请求。
 *
 * @param command 命令及参数列表
 * @param workingDirectory 工作目录
 * @param environment 允许注入的环境变量
 * @param timeout 超时时间
 */
public record ScriptExecutionRequest(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout
) {
}
