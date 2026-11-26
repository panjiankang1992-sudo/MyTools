package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** 通过受限离线子进程使用成熟正文提取器，临时页面不会进入模型或历史报告。 */
final class OfflineArticleExtractor {
    private OfflineArticleExtractor() { }

    static String extract(String html, String url, long deadline) {
        String script = System.getProperty("news.contentExtractor", "");
        long remaining = Math.min(10_000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (script.isBlank() || remaining <= 0 || html.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) return "";
        Path temporary = null;
        Process process = null;
        try {
            temporary = Files.createTempDirectory("news-article-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path input = temporary.resolve("input.html"), output = temporary.resolve("output.json");
            Files.writeString(input, html);
            // 命令由受信运行包配置，网页和地址仅作为参数及数据文件传入，不使用 shell。
            process = new ProcessBuilder(System.getProperty("news.contentPython", "python3"), script,
                    input.toString(), output.toString(), url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); return ""; }
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) > 65536) return "";
            return new ObjectMapper().readTree(output.toFile()).path("text").asText();
        } catch (IOException ignored) {
            return ""; // 组件缺失或异常时继续原有 HTML 和公开表示策略，不阻断整份日报。
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.resolve("input.html"));
                    Files.deleteIfExists(temporary.resolve("output.json"));
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) { /* 临时文件清理失败不覆盖已有正文获取结果。 */ }
            }
        }
    }
}
