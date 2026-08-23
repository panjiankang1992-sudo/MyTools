package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.dto.DirectoryNameCleanupTask;
import com.yuyutian.mytools.localfile.dto.DirectoryRenameProposal;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 媒体目录名称净化异步任务服务。
 */
@Slf4j
@Service
public class DirectoryNameCleanupTaskService {

    private final DirectoryNameCleanupService cleanupService;
    private final LocalDirectoryMapper directoryMapper;
    private final Executor executor;
    private final Map<String, DirectoryNameCleanupTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, String> activeTasks = new ConcurrentHashMap<>();

    /**
     * 创建目录名称净化任务服务。
     *
     * @param cleanupService 目录名称净化服务
     * @param directoryMapper 受管目录Mapper
     * @param executor 本地文件后台执行器
     */
    public DirectoryNameCleanupTaskService(DirectoryNameCleanupService cleanupService,
                                           LocalDirectoryMapper directoryMapper,
                                           @Qualifier("localFileScanExecutor") Executor executor) {
        this.cleanupService = cleanupService;
        this.directoryMapper = directoryMapper;
        this.executor = executor;
    }

    /**
     * 提交目录名称净化预览或应用任务。
     *
     * @param directoryId 受管目录ID
     * @param apply 是否应用安全建议
     * @return 后台任务状态
     */
    public synchronized DirectoryNameCleanupTask submit(Long directoryId, boolean apply) {
        LocalDirectory directory = directoryMapper.selectById(directoryId);
        if (directory == null) {
            throw new BusinessException(ErrorCode.FILE_010);
        }
        if (!"MULTIMEDIA".equals(directory.getDirectoryType())
                && !"LARGE_MEDIA".equals(directory.getDirectoryType())) {
            throw new BusinessException(ErrorCode.FILE_009);
        }
        String activeTaskId = activeTasks.get(directoryId);
        if (activeTaskId != null) {
            DirectoryNameCleanupTask activeTask = tasks.get(activeTaskId);
            if (activeTask != null && ("PENDING".equals(activeTask.getStatus())
                    || "RUNNING".equals(activeTask.getStatus()))) {
                return activeTask;
            }
        }

        DirectoryNameCleanupTask task = new DirectoryNameCleanupTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setDirectoryId(directoryId);
        task.setApply(apply);
        task.setStatus("PENDING");
        task.setCheckedCount(0);
        task.setProposedCount(0);
        task.setRenamedCount(0);
        task.setReviewCount(0);
        task.setProposals(List.of());
        task.setCreateTime(LocalDateTime.now());
        tasks.put(task.getTaskId(), task);
        activeTasks.put(directoryId, task.getTaskId());
        executor.execute(() -> execute(task));
        return task;
    }

    /**
     * 获取目录名称净化任务状态。
     *
     * @param taskId 任务ID
     * @return 后台任务状态
     */
    public DirectoryNameCleanupTask getTask(String taskId) {
        DirectoryNameCleanupTask task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }
        return task;
    }

    private void execute(DirectoryNameCleanupTask task) {
        task.setStatus("RUNNING");
        try {
            List<DirectoryRenameProposal> proposals = cleanupService.preview(task.getDirectoryId());
            task.setProposals(proposals);
            task.setCheckedCount(proposals.size());
            task.setProposedCount((int) proposals.stream()
                    .filter(proposal -> "READY".equals(proposal.status())).count());
            task.setReviewCount((int) proposals.stream()
                    .filter(DirectoryRenameProposal::needsReview).count());
            if (Boolean.TRUE.equals(task.getApply())) {
                task.setRenamedCount(cleanupService.apply(task.getDirectoryId(), proposals));
            }
            task.setStatus("COMPLETED");
        } catch (RuntimeException ex) {
            log.error("媒体目录名称净化任务失败：taskId={}", task.getTaskId(), ex);
            task.setStatus("FAILED");
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishTime(LocalDateTime.now());
            activeTasks.remove(task.getDirectoryId(), task.getTaskId());
        }
    }
}
