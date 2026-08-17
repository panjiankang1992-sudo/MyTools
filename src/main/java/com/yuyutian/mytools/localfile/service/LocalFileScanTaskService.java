package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.dto.ScanResult;
import com.yuyutian.mytools.localfile.dto.ScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 本地目录扫描后台任务服务。
 */
@Slf4j
@Service
public class LocalFileScanTaskService {

    private final LocalFileService localFileService;

    private final Executor scanExecutor;

    private final Map<String, ScanTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, String> activeDirectoryTasks = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> pendingDirectoryScans = new ConcurrentHashMap<>();

    /**
     * 创建后台扫描任务服务。
     *
     * @param localFileService 本地文件服务
     * @param scanExecutor 扫描任务执行器
     */
    public LocalFileScanTaskService(LocalFileService localFileService,
                                    @Qualifier("localFileScanExecutor") Executor scanExecutor) {
        this.localFileService = localFileService;
        this.scanExecutor = scanExecutor;
    }

    /**
     * 提交目录扫描任务，同一目录同时只允许一个扫描任务。
     *
     * @param directoryId 目录ID
     * @param fullScan 是否全量扫描
     * @return 扫描任务状态
     */
    public synchronized ScanTask submitScan(Long directoryId, boolean fullScan) {
        String activeTaskId = activeDirectoryTasks.get(directoryId);
        if (activeTaskId != null) {
            ScanTask activeTask = tasks.get(activeTaskId);
            if (activeTask != null && ("PENDING".equals(activeTask.getStatus())
                    || "RUNNING".equals(activeTask.getStatus()))) {
                // 扫描期间发生的新事件需要在当前任务结束后补扫，避免事件丢失。
                pendingDirectoryScans.merge(directoryId, fullScan, Boolean::logicalOr);
                return activeTask;
            }
        }

        ScanTask task = new ScanTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setDirectoryId(directoryId);
        task.setStatus("PENDING");
        task.setScannedCount(0);
        task.setNewCount(0);
        task.setCreateTime(LocalDateTime.now());
        tasks.put(task.getTaskId(), task);
        activeDirectoryTasks.put(directoryId, task.getTaskId());

        scanExecutor.execute(() -> executeScan(task, fullScan));
        return task;
    }

    /**
     * 获取扫描任务状态。
     *
     * @param taskId 任务ID
     * @return 扫描任务状态
     */
    public ScanTask getTask(String taskId) {
        ScanTask task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }
        return task;
    }

    private void executeScan(ScanTask task, boolean fullScan) {
        task.setStatus("RUNNING");
        try {
            ScanResult result = localFileService.scanDirectory(task.getDirectoryId(), fullScan);
            task.setScannedCount(result.getScannedCount());
            task.setNewCount(result.getNewCount());
            task.setStatus("COMPLETED");
        } catch (Exception ex) {
            log.error("目录扫描后台任务失败：taskId={}", task.getTaskId(), ex);
            task.setStatus("FAILED");
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishTime(LocalDateTime.now());
            activeDirectoryTasks.remove(task.getDirectoryId(), task.getTaskId());
            Boolean pendingFullScan = pendingDirectoryScans.remove(task.getDirectoryId());
            if (pendingFullScan != null) {
                // 当前任务完成后立即提交合并后的补偿扫描。
                submitScan(task.getDirectoryId(), pendingFullScan);
            }
        }
    }
}
