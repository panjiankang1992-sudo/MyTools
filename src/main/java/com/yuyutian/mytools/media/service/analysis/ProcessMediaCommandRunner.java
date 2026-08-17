package com.yuyutian.mytools.media.service.analysis;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 不经过 Shell 的 FFmpeg 和 ffprobe 进程执行器。
 */
@Component
public class ProcessMediaCommandRunner implements MediaCommandRunner {

    /** {@inheritDoc} */
    @Override
    public String runForOutput(List<String> command, Duration timeout, int maxOutputBytes) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> copyBounded(process.getInputStream(), output, maxOutputBytes));
        waitFor(process, reader, timeout);
        if (process.exitValue() != 0) {
            throw new IOException("Media metadata command failed");
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** {@inheritDoc} */
    @Override
    public void runForFile(List<String> command, Duration timeout, Path output) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        waitFor(process, null, timeout);
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 2) {
            throw new IOException("Media file generation command failed");
        }
    }

    private void copyBounded(InputStream input, ByteArrayOutputStream output, int maxOutputBytes) {
        byte[] buffer = new byte[4096];
        int total = 0;
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total <= maxOutputBytes) {
                    output.write(buffer, 0, count);
                }
            }
        } catch (IOException ignored) {
            // 主线程根据进程状态和输出解析统一处理错误。
        }
    }

    private void waitFor(Process process, Thread reader, Duration timeout) throws IOException {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Media command timed out");
            }
            if (reader != null) {
                reader.join(TimeUnit.SECONDS.toMillis(2));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Media command was interrupted", ex);
        }
    }
}
