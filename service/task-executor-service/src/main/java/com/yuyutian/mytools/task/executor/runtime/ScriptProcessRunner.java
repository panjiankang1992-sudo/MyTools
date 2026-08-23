package com.yuyutian.mytools.task.executor.runtime;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * 使用独立进程执行已解析命令，不经过 Shell 字符串拼接。
 */
@Component
public class ScriptProcessRunner {

    /**
     * 执行脚本进程并收集有限输出。
     *
     * @param request 执行请求
     * @return 执行结果
     * @throws IOException 进程或文件操作失败
     */
    public ScriptExecutionResult run(ScriptExecutionRequest request) throws IOException {
        Files.createDirectories(request.workingDirectory());
        ProcessBuilder builder = new ProcessBuilder(request.command());
        builder.directory(request.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(new HashMap<>(request.environment()));
        Instant startedAt = Instant.now();
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Script execution was interrupted", exception);
        }
        if (!finished) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        String stdout = new String(process.getInputStream().readNBytes(1_048_576), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readNBytes(1_048_576), StandardCharsets.UTF_8);
        int exitCode = finished ? process.exitValue() : -1;
        return new ScriptExecutionResult(exitCode, stdout, stderr, Duration.between(startedAt, Instant.now()), !finished);
    }
}
