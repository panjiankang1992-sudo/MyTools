package com.yuyutian.mytools.media.job;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.service.analysis.MediaPackageAnalysisService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 持续发现并分析 DownloadBot 发布的大视频资源包。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaPackageAnalysisJob {

    private static final int BATCH_SIZE = 20;
    private static final int CANDIDATE_QUERY_LIMIT = 2000;

    private final LocalDirectoryMapper localDirectoryMapper;
    private final LocalFileMapper localFileMapper;
    private final MediaPackageAnalysisService analysisService;
    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(2);
    private final Set<Path> activePackages = ConcurrentHashMap.newKeySet();

    /**
     * 扫描待处理资源包并异步提交，状态由资源包清单持久化。
     */
    @Scheduled(fixedDelayString = "${media.analysis.scan-delay-ms:15000}",
            initialDelayString = "${media.analysis.initial-delay-ms:30000}")
    public void analyzePendingPackages() {
        LocalDirectory directory = localDirectoryMapper.selectByType("LARGE_MEDIA");
        if (directory == null || directory.getScanEnabled() == null || directory.getScanEnabled() != 1
                || directory.getDirectoryPath() == null || directory.getDirectoryPath().isBlank()) {
            return;
        }
        String root = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize().toString();
        int submitted = 0;
        for (LocalFile file : localFileMapper.selectMediaPackageCandidates(root, CANDIDATE_QUERY_LIMIT)) {
            if (submitted >= BATCH_SIZE) {
                break;
            }
            Path packageDirectory = Path.of(file.getFilePath()).toAbsolutePath().normalize().getParent();
            if (packageDirectory == null || !analysisService.needsAnalysis(packageDirectory)
                    || !activePackages.add(packageDirectory)) {
                continue;
            }
            analysisExecutor.submit(() -> analyze(packageDirectory));
            submitted++;
        }
    }

    private void analyze(Path packageDirectory) {
        try {
            analysisService.analyze(packageDirectory);
            log.info("媒体资源包分析完成：{}", packageDirectory);
        } catch (Exception ex) {
            log.warn("媒体资源包分析失败：{}", packageDirectory, ex);
        } finally {
            activePackages.remove(packageDirectory);
        }
    }

    /**
     * 关闭媒体分析工作线程。
     */
    @PreDestroy
    public void shutdownExecutor() {
        analysisExecutor.shutdown();
        try {
            if (!analysisExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            analysisExecutor.shutdownNow();
        }
    }
}
