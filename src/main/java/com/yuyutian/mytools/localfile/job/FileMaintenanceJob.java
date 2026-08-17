package com.yuyutian.mytools.localfile.job;

import com.yuyutian.mytools.localfile.service.FileMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件增量维护定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileMaintenanceJob {

    private final FileMaintenanceService fileMaintenanceService;

    /**
     * 定期为新增文件执行MD5去重。
     */
    @Scheduled(fixedDelayString = "${file.maintenance.md5-fixed-delay-ms:600000}",
            initialDelayString = "${file.maintenance.md5-initial-delay-ms:120000}")
    public void deduplicateNewFiles() {
        try {
            int submittedCount = fileMaintenanceService.submitIncrementalExactDeduplication();
            if (submittedCount > 0) {
                log.info("已提交增量MD5去重任务：count={}", submittedCount);
            }
        } catch (Exception ex) {
            log.error("提交增量MD5去重任务失败", ex);
        }
    }
}
