package com.yuyutian.mytools.reader.job;

import com.yuyutian.mytools.reader.service.BookSourceChapterCache;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 网络书源章节缓存定期清理任务。
 */
@Component
@RequiredArgsConstructor
public class BookSourceChapterCacheJob {
    private final BookSourceChapterCache chapterCache;

    /**
     * 清理过期和超量章节缓存。
     */
    @Scheduled(fixedDelayString = "${mytools.reader-runtime.chapter-cache-prune-delay-ms:3600000}",
            initialDelayString = "${mytools.reader-runtime.chapter-cache-prune-initial-delay-ms:60000}")
    public void prune() {
        chapterCache.prune();
    }
}
