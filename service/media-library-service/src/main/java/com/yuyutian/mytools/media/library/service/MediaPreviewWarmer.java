package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.model.MediaModels.MediaView;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 在资产事务提交后使用一个后台工作线程预热图片，前台仍可按需生成。 */
@Component
@ConditionalOnProperty(name = "media-library.preview-warm-enabled", havingValue = "true", matchIfMissing = true)
public class MediaPreviewWarmer {
    private static final Logger LOG = LoggerFactory.getLogger(MediaPreviewWarmer.class);
    private final MediaImagePreviewService previews;
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("image-preview-warm").factory());

    /** 创建有界的预览预热队列。 */
    public MediaPreviewWarmer(MediaImagePreviewService previews, JdbcTemplate jdbc,
            @Value("${media-library.preview-cache-directory:/opt/yuyutian/mytools/runtime/media-previews-v1}") String directory) {
        this.previews = previews;
        this.jdbc = jdbc;
        this.directory = Path.of(directory);
    }

    /** 服务启动后异步回补最近入库的图片。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recent() {
        worker.execute(() -> {
            try {
                jdbc.query("SELECT id,owner_id FROM media_item WHERE status='READY' AND mime_type LIKE 'image/%' " +
                                "AND size_bytes BETWEEN 1 AND 20971520 ORDER BY created_at DESC,id DESC LIMIT 48",
                        (org.springframework.jdbc.core.RowCallbackHandler) row ->
                                enqueue(UUID.fromString(row.getString(1)), row.getLong(2)));
            } catch (RuntimeException ex) {
                LOG.warn("Recent preview backfill unavailable: {}", ex.getClass().getSimpleName());
            }
        });
    }

    /** 只在入库事务提交后排队，避免回滚事务生成不可用预览。 */
    @TransactionalEventListener
    public void imported(MediaView media) {
        if ("READY".equals(media.status()) && media.mimeType().startsWith("image/") &&
                media.sizeBytes() > 0 && media.sizeBytes() <= 20L * 1024 * 1024) {
            enqueue(media.id(), media.ownerId());
        }
    }

    private void enqueue(UUID id, long owner) {
        if (!pending.add(id)) return;
        try {
            worker.execute(() -> {
                try {
                    Files.createDirectories(directory);
                    // 低水位时把容量留给真实请求，缓存键和所有权校验由前台服务统一处理。
                    if (Files.getFileStore(directory).getUsableSpace() < 2L * 1024 * 1024 * 1024) return;
                    previews.preview(owner, id, 512);
                    previews.preview(owner, id, 2048);
                } catch (Exception failure) {
                    LOG.debug("Preview warm skipped: {}", failure.getClass().getSimpleName());
                } finally {
                    pending.remove(id);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // 超出容量的任务由前台按需生成，不反压入库事务。
            pending.remove(id);
        }
    }

    /** 服务停止时释放队列并中止后台任务。 */
    @PreDestroy
    public void close() {
        worker.shutdownNow();
        pending.clear();
    }
}
