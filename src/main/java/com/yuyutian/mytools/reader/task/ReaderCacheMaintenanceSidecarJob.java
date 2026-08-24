package com.yuyutian.mytools.reader.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reader 新 schema 章节缓存维护触发器。
 */
@Slf4j
@Component
public class ReaderCacheMaintenanceSidecarJob {
    private final ReaderCacheMaintenanceSidecarClient client;
    private final ReaderCacheMaintenanceSidecarProperties properties;

    /**
     * 创建章节缓存维护触发器。
     *
     * @param client Reader Service 客户端
     * @param properties 旁路配置
     */
    public ReaderCacheMaintenanceSidecarJob(ReaderCacheMaintenanceSidecarClient client,
                                            ReaderCacheMaintenanceSidecarProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 按小时创建一次新 schema 过期缓存清理任务。
     */
    @Scheduled(fixedDelayString = "${migration.tasks.reader-cache-maintenance.delay-ms:3600000}",
            initialDelayString = "${migration.tasks.reader-cache-maintenance.initial-delay-ms:60000}")
    public void submit() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant cutoffAt = Instant.now().truncatedTo(ChronoUnit.HOURS);
        try {
            ReaderCacheMaintenanceSidecarClient.MaintenanceAccepted accepted = client.create(
                    cutoffAt, "reader-cache-maintenance:" + cutoffAt.getEpochSecond());
            log.info("Reader cache maintenance created: maintenanceId={}, taskId={}",
                    accepted.id(), accepted.taskId());
        } catch (RuntimeException exception) {
            log.warn("Reader cache maintenance creation failed: error={}", exception.getMessage());
        }
    }
}
