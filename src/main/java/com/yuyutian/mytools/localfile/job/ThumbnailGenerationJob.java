package com.yuyutian.mytools.localfile.job;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import com.yuyutian.mytools.media.task.MediaProcessingSidecarRequested;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 媒体缩略图后台生成任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailGenerationJob {

    private static final int BATCH_SIZE = 24;

    private final LocalFileMapper localFileMapper;
    private final LocalFileService localFileService;
    private final ResourceStorageGuard resourceStorageGuard;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);
    private final Map<Long, Integer> thumbnailFailureCounts = new ConcurrentHashMap<>();
    private final Map<Long, Long> thumbnailRetryAfter = new ConcurrentHashMap<>();

    @Value("${file.scan.path:D:/MyFiles}")
    private String scanPath;

    @Value("${file.scan.thumbnail-path:D:/MyFiles/.thumbnails}")
    private String thumbnailPath;

    private long lastProcessedId;

    /**
     * 持续补齐所有历史及新增媒体文件的缩略图。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void generateMissingThumbnails() {
        if (!resourceStorageGuard.isAvailable()) {
            // 资源盘掉线时不启动 FFmpeg，避免无意义的失败重试。
            return;
        }
        String mediaPath = Paths.get(scanPath, "media").toAbsolutePath().normalize().toString();
        String normalizedThumbnailPath = Paths.get(thumbnailPath).toAbsolutePath().normalize().toString();
        List<LocalFile> candidates = localFileMapper.selectThumbnailCandidates(
                mediaPath, normalizedThumbnailPath, lastProcessedId, BATCH_SIZE);
        if (candidates.isEmpty()) {
            // 完成一轮后从头检查失败文件和后续新增文件。
            lastProcessedId = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        List<LocalFile> readyCandidates = candidates.stream()
                .filter(file -> thumbnailRetryAfter.getOrDefault(file.getId(), 0L) <= now)
                .toList();
        if (readyCandidates.isEmpty()) {
            // 已知损坏文件进入退避期，继续推进游标，避免每十秒重复拉起FFmpeg。
            lastProcessedId = candidates.get(candidates.size() - 1).getId();
            return;
        }

        try {
            thumbnailExecutor.invokeAll(readyCandidates.stream()
                    .<java.util.concurrent.Callable<Void>>map(file -> () -> {
                        try {
                            localFileService.generateAndPersistThumbnail(file.getId());
                            thumbnailFailureCounts.remove(file.getId());
                            thumbnailRetryAfter.remove(file.getId());
                            // 旧缩略图仍为权威结果，旁路任务只做独立生成与对账准备。
                            applicationEventPublisher.publishEvent(new MediaProcessingSidecarRequested(
                                    file.getId(), file.getFilePath(), file.getFileHash(),
                                    file.getMimeType()));
                        } catch (Exception ex) {
                            recordThumbnailFailure(file.getId());
                            log.warn("后台生成缩略图失败，文件ID：{}，路径：{}", file.getId(), file.getFilePath(), ex);
                        }
                        return null;
                    }).toList());
            lastProcessedId = candidates.get(candidates.size() - 1).getId();
            log.info("后台缩略图批次完成，处理数量：{}，游标：{}", readyCandidates.size(), lastProcessedId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("后台缩略图任务被中断", ex);
        }
    }

    private void recordThumbnailFailure(Long fileId) {
        int failureCount = thumbnailFailureCounts.merge(fileId, 1, Integer::sum);
        long delay = switch (Math.min(failureCount, 4)) {
            case 1 -> TimeUnit.MINUTES.toMillis(5);
            case 2 -> TimeUnit.MINUTES.toMillis(15);
            case 3 -> TimeUnit.HOURS.toMillis(1);
            default -> TimeUnit.HOURS.toMillis(6);
        };
        thumbnailRetryAfter.put(fileId, System.currentTimeMillis() + delay);
    }

    /**
     * 关闭缩略图工作线程。
     */
    @PreDestroy
    public void shutdownExecutor() {
        thumbnailExecutor.shutdown();
        try {
            if (!thumbnailExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                thumbnailExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            thumbnailExecutor.shutdownNow();
        }
    }
}
