package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 跨文件系统复制、校验和目标文件系统内原子切换服务。
 */
@Component
public class CrossFileSystemPublisher {

    /**
     * 将已校验的暂存文件安全发布到另一个文件系统。
     *
     * @param source 来源暂存文件
     * @param target 最终目标文件
     * @param expectedSize 期望大小
     * @param expectedSha256 期望摘要
     * @throws IOException 文件操作失败
     */
    public void publish(Path source, Path target, long expectedSize, String expectedSha256) throws IOException {
        Path targetDirectory = target.getParent();
        Path copied = targetDirectory.resolve(".mytools-publish-" + UUID.randomUUID() + ".part");
        boolean published = false;
        try {
            CopyDigest digest = copyAndDigest(source, copied);
            if (digest.size() != expectedSize || !digest.sha256().equals(expectedSha256)) {
                throw new IOException(ErrorCode.CONTENT_MISMATCH.code());
            }
            // 临时副本与最终文件位于同一目录，只有原子切换成功才视为发布完成。
            try {
                Files.move(copied, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(ErrorCode.IO_FAILURE.code(), exception);
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(copied);
            }
        }
        // 目标已原子发布，来源清理失败不影响结果正确性，可由清理任务回收。
        try {
            Files.deleteIfExists(source);
        } catch (IOException ignored) {
            // 来源暂存文件不再是权威副本。
        }
    }

    private CopyDigest copyAndDigest(Path source, Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(source);
                 OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE);
                 DigestOutputStream output = new DigestOutputStream(file, digest)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    size += read;
                }
            }
            return new CopyDigest(size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ErrorCode.IO_FAILURE.code(), exception);
        }
    }

    private record CopyDigest(long size, String sha256) {
    }
}
