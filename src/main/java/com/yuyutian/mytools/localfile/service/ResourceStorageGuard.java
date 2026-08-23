package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 资源盘健康检查与数据清理保护服务。
 */
@Slf4j
@Service
public class ResourceStorageGuard {

    @Value("${file.resource.mount-path:}")
    private String mountPath;

    @Value("${file.resource.require-mount:false}")
    private boolean requireMount;

    @Value("${file.scan.path:D:/MyFiles}")
    private String scanPath;

    @Value("${file.scan.thumbnail-path:D:/MyFiles/.thumbnails}")
    private String thumbnailPath;

    /**
     * 获取资源盘当前健康状态。
     *
     * @return 健康状态
     */
    public StorageStatus status() {
        Path root = Path.of(scanPath).toAbsolutePath().normalize();
        Path media = root.resolve("media");
        Path thumbnails = Path.of(thumbnailPath).toAbsolutePath().normalize();
        boolean mounted = !requireMount || isMounted(Path.of(mountPath).toAbsolutePath().normalize());
        boolean rootReadable = isReadableDirectory(root);
        boolean mediaReadable = isReadableDirectory(media);
        boolean thumbnailsReadable = isReadableDirectory(thumbnails);
        String reason = determineReason(mounted, rootReadable, mediaReadable, thumbnailsReadable);
        return new StorageStatus("available".equals(reason), mounted, rootReadable, mediaReadable,
                thumbnailsReadable, reason, Instant.now());
    }

    /**
     * 判断资源盘是否可用。
     *
     * @return 可用时返回 true
     */
    public boolean isAvailable() {
        return status().available();
    }

    /**
     * 在执行历史路径清理前校验资源盘和受管目录。
     *
     * @param managedPath 待清理的受管目录
     */
    public void requireAvailableForCleanup(Path managedPath) {
        StorageStatus current = status();
        Path root = Path.of(scanPath).toAbsolutePath().normalize();
        Path normalizedPath = managedPath.toAbsolutePath().normalize();
        if (!current.available() || !normalizedPath.startsWith(root)) {
            log.error("资源盘不可用或目录越界，已禁止数据库历史路径清理：reason={}", current.reason());
            throw new BusinessException(ErrorCode.FILE_012);
        }
    }

    private boolean isReadableDirectory(Path path) {
        return Files.isDirectory(path) && Files.isReadable(path);
    }

    private String determineReason(boolean mounted, boolean rootReadable, boolean mediaReadable,
                                   boolean thumbnailsReadable) {
        if (!mounted) return "mount-missing";
        if (!rootReadable) return "root-missing";
        if (!mediaReadable) return "media-missing";
        if (!thumbnailsReadable) return "thumbnails-missing";
        return "available";
    }

    private boolean isMounted(Path expectedMountPath) {
        if (mountPath == null || mountPath.isBlank() || !Files.isDirectory(expectedMountPath)) return false;
        Path mountInfo = Path.of("/proc/self/mountinfo");
        if (Files.isRegularFile(mountInfo)) {
            try {
                // Linux mountinfo 的第五列是当前命名空间中的挂载点。
                return Files.readAllLines(mountInfo).stream()
                        .map(line -> line.split(" "))
                        .filter(parts -> parts.length > 4)
                        .map(parts -> unescapeMountPath(parts[4]))
                        .anyMatch(expectedMountPath.toString()::equals);
            } catch (IOException ex) {
                log.warn("读取系统挂载信息失败", ex);
                return false;
            }
        }
        try {
            Path parent = expectedMountPath.getParent();
            return parent != null && !Files.getFileStore(expectedMountPath).equals(Files.getFileStore(parent));
        } catch (IOException ex) {
            return false;
        }
    }

    private String unescapeMountPath(String value) {
        return value.replace("\\040", " ")
                .replace("\\011", "\t")
                .replace("\\012", "\n")
                .replace("\\134", "\\");
    }

    /**
     * 资源盘健康快照。
     *
     * @param available 整体是否可用
     * @param mounted 挂载点是否存在
     * @param rootReadable 资源根目录是否可读
     * @param mediaReadable 媒体目录是否可读
     * @param thumbnailsReadable 缩略图目录是否可读
     * @param reason 状态原因
     * @param checkedAt 检查时间
     */
    public record StorageStatus(boolean available, boolean mounted, boolean rootReadable,
                                boolean mediaReadable, boolean thumbnailsReadable,
                                String reason, Instant checkedAt) {
    }
}
