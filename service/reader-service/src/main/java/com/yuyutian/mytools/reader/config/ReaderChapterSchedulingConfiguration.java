package com.yuyutian.mytools.reader.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 仅在书架章节准备明确开启时启用后台轮询。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "reader.shelf-chapters", name = "enabled", havingValue = "true")
public class ReaderChapterSchedulingConfiguration {
    /** 启用时核对最坏 HTTP 时间与任务租约，避免默认配置在取目录期间必然过期。 */
    public ReaderChapterSchedulingConfiguration(ReaderShelfChapterProperties shelf, ReaderChapterContentProperties content) {
        if (shelf.claimSeconds() < content.requestTimeoutSeconds() * 4 + 15) {
            throw new IllegalStateException("Shelf preparation lease cannot cover runtime request budget");
        }
    }
}
