package com.yuyutian.mytools.media.task;

import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 旧媒体根目录的任务化扫描旁路。
 */
@Slf4j
@Component
public class MediaDirectoryScanSidecarJob {
    private final MediaDirectoryScanSidecarClient client;
    private final MediaDirectoryScanSidecarProperties properties;
    private final ResourceStorageGuard storageGuard;
    private final String scanPath;

    /**
     * 创建媒体目录扫描旁路任务。
     *
     * @param client Media Library 客户端
     * @param properties 旁路配置
     * @param storageGuard 资源盘保护器
     * @param scanPath 旧媒体扫描目录
     */
    public MediaDirectoryScanSidecarJob(MediaDirectoryScanSidecarClient client,
                                        MediaDirectoryScanSidecarProperties properties,
                                        ResourceStorageGuard storageGuard,
                                        @Value("${file.scan.path:D:/MyFiles}") String scanPath) {
        this.client = client;
        this.properties = properties;
        this.storageGuard = storageGuard;
        this.scanPath = scanPath;
    }

    /**
     * 每日为旧媒体根目录创建一次可追踪扫描任务。
     */
    @Scheduled(cron = "${migration.tasks.media-directory-scan.cron:0 15 2 * * ?}")
    public void submit() {
        if (!properties.isEnabled() || !storageGuard.isAvailable()) {
            return;
        }
        try {
            String normalizedPath = Path.of(scanPath).toAbsolutePath().normalize().toString();
            String idempotencyKey = "legacy-directory-scan:" + LocalDate.now() + ":" + pathKey(normalizedPath);
            MediaDirectoryScanSidecarClient.ScanAccepted accepted = client.create(normalizedPath, idempotencyKey);
            log.info("Media directory scan sidecar created: operationId={}, taskInstanceId={}",
                    accepted.id(), accepted.taskInstanceId());
        } catch (RuntimeException exception) {
            log.warn("Media directory scan sidecar creation failed: error={}", exception.getMessage());
        }
    }

    private String pathKey(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
