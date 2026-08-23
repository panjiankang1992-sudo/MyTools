package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户级书源详情、目录与正文读取服务。
 */
@Service
@RequiredArgsConstructor
public class BookSourceRuntimeReaderService {
    private final ReaderRuntimeClient runtimeClient;
    private final BookSourceChapterCache chapterCache;

    /**
     * 加载图书详情和目录。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param bookUrl 图书地址
     * @return 图书目录
     */
    public BookSourceRuntimeReaderModels.Catalog catalog(Long userId, String sourceUrl, String bookUrl) {
        return runtimeClient.catalog(userId, sourceUrl.trim(), bookUrl.trim());
    }

    /**
     * 加载指定章节正文。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param chapterUrl 章节地址
     * @param chapterIndex 章节序号
     * @return 章节正文
     */
    public BookSourceRuntimeReaderModels.Content content(Long userId, String sourceUrl,
                                                          String chapterUrl, int chapterIndex) {
        String normalizedSourceUrl = sourceUrl.trim();
        String normalizedChapterUrl = chapterUrl.trim();
        return chapterCache.get(userId, normalizedSourceUrl, normalizedChapterUrl, chapterIndex)
                .orElseGet(() -> {
                    BookSourceRuntimeReaderModels.Content content = runtimeClient.content(userId,
                            normalizedSourceUrl, normalizedChapterUrl, chapterIndex);
                    chapterCache.put(userId, normalizedSourceUrl, normalizedChapterUrl, chapterIndex, content);
                    return content;
                });
    }
}
