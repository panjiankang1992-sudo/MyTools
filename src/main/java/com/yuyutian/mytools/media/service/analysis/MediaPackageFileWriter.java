package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写入媒体资源包伴生文件。
 */
@Component
@RequiredArgsConstructor
public class MediaPackageFileWriter {

    private final ObjectMapper objectMapper;

    /**
     * 原子写入 UTF-8 文本。
     *
     * @param target 目标文件
     * @param content 文本内容
     * @throws IOException 写入失败
     */
    public void writeText(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 原子写入 JSON。
     *
     * @param target 目标文件
     * @param content JSON 内容
     * @throws IOException 写入失败
     */
    public void writeJson(Path target, JsonNode content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), content);
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 原子复制文件。
     *
     * @param source 源文件
     * @param target 目标文件
     * @throws IOException 复制失败
     */
    public void copy(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
