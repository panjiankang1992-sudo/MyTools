package com.yuyutian.mytools.media.job;

import com.yuyutian.mytools.media.service.importer.ManualVideoPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期补偿人工大视频资源包整理，文件监听仅负责降低首次扫描延迟。
 */
@Component
@RequiredArgsConstructor
public class ManualVideoPackageJob {

    private final ManualVideoPackageService packageService;

    /** 执行一批幂等整理任务。 */
    @Scheduled(fixedDelayString = "${media.manual-package.scan-delay-ms:30000}",
            initialDelayString = "${media.manual-package.initial-delay-ms:45000}")
    public void packagePendingVideos() {
        packageService.packagePendingVideos();
    }
}
