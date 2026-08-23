package com.yuyutian.mytools.reader.job;

import com.yuyutian.mytools.reader.model.EbookIndexResult;
import com.yuyutian.mytools.reader.service.EbookCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持续为历史和新增电子书补齐领域元数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EbookMetadataIndexJob {
    private final EbookCatalogService ebookCatalogService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${mytools.ebook.index-enabled:true}")
    private boolean enabled;

    @Value("${mytools.ebook.index-batch-size:50}")
    private int batchSize;

    /**
     * 分批处理待索引电子书，避免启动时长时间占用数据库和磁盘。
     */
    @Scheduled(fixedDelayString = "${mytools.ebook.index-delay-ms:30000}",
            initialDelayString = "${mytools.ebook.index-initial-delay-ms:15000}")
    public void indexPendingBooks() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            EbookIndexResult result = ebookCatalogService.index(null, batchSize);
            if (result.indexed() > 0 || result.failed() > 0) {
                log.info("电子书元数据批次完成：成功={}，失败={}，剩余={}",
                        result.indexed(), result.failed(), result.remaining());
            }
        } catch (Exception exception) {
            log.warn("电子书元数据后台索引失败", exception);
        } finally {
            running.set(false);
        }
    }
}
