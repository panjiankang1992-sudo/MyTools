package com.yuyutian.mytools.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件读取工具类。
 * 提供从classpath、文件系统读取文件内容的方法。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Component
public class FileUtils {

    /**
     * 从classpath资源读取文件内容。
     *
     * @param resourcePath 资源路径
     * @return 文件内容字符串
     * @throws IOException 如果读取失败
     */
    public String readFromClasspath(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append(System.lineSeparator());
            }
            log.debug("从classpath读取文件成功: {}", resourcePath);
            return content.toString();
        }
    }

    /**
     * 从文件系统读取文件内容。
     *
     * @param filePath 文件绝对路径
     * @return 文件内容字符串
     * @throws IOException 如果读取失败
     */
    public String readFromFileSystem(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + filePath);
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        log.debug("从文件系统读取文件成功: {}", filePath);
        return content;
    }

    /**
     * 检查文件是否存在。
     *
     * @param filePath 文件路径
     * @return 是否存在
     */
    public boolean exists(String filePath) {
        ClassPathResource resource = new ClassPathResource(filePath);
        if (resource.exists()) {
            return true;
        }
        Path path = Paths.get(filePath);
        return Files.exists(path);
    }
}
