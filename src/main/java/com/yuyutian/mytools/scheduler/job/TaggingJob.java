package com.yuyutian.mytools.scheduler.job;

import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时打标签任务。
 * 定期处理未打标签的文件，调用打标签服务进行标签识别。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaggingJob {

    private final TaggerService taggerService;

    /** 每次处理的批次大小 */
    private static final int BATCH_SIZE = 10;

    /**
     * 每小时执行一次打标签任务。
     * 从未打标签的文件队列中获取文件进行处理。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void processUntaggedFiles() {
        log.info("开始执行定时打标签任务");
        try {
            int successCount = taggerService.processUntaggedFiles(BATCH_SIZE);
            log.info("定时打标签任务完成，成功处理: {} 个文件", successCount);
        } catch (Exception e) {
            log.error("定时打标签任务执行失败", e);
        }
    }
}
