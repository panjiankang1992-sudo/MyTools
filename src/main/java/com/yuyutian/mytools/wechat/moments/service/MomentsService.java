package com.yuyutian.mytools.wechat.moments.service;

import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.wechat.account.mapper.WechatAccountMapper;
import com.yuyutian.mytools.wechat.account.model.AccountStatus;
import com.yuyutian.mytools.wechat.account.model.WechatAccount;
import com.yuyutian.mytools.wechat.moments.mapper.MomentsMediaMapper;
import com.yuyutian.mytools.wechat.moments.mapper.MomentsTaskMapper;
import com.yuyutian.mytools.wechat.moments.model.MomentsMedia;
import com.yuyutian.mytools.wechat.moments.model.MomentsTask;
import com.yuyutian.mytools.wechat.moments.model.TaskStatus;
import com.yuyutian.mytools.wechat.moments.model.dto.BatchCreateTaskRequest;
import com.yuyutian.mytools.wechat.moments.model.dto.CreateTaskRequest;
import com.yuyutian.mytools.wechat.moments.model.dto.UpdateTaskRequest;
import com.yuyutian.mytools.wechat.moments.model.dto.TaskListResponse;
import com.yuyutian.mytools.wechat.moments.model.dto.RefreshTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

/**
 * 朋友圈任务服务层
 */
@Service
@RequiredArgsConstructor
public class MomentsService {

    private final MomentsTaskMapper taskMapper;
    private final MomentsMediaMapper mediaMapper;
    private final WechatAccountMapper accountMapper;

    /**
     * 分页查询任务列表
     */
    public TaskListResponse getTaskPage(Long page, Long pageSize, Long accountId, Integer status, Boolean includeExpired, String keyword) {
        Long offset = (page - 1) * pageSize;
        List<MomentsTask> list = taskMapper.selectPage(offset, pageSize, accountId, status, includeExpired, keyword);
        Long total = taskMapper.count(accountId, status, includeExpired, keyword);
        // 填充计算字段
        for (MomentsTask task : list) {
            // 计算 firstMediaUrl
            List<MomentsMedia> mediaList = mediaMapper.selectByTaskId(task.getId());
            if (mediaList != null && !mediaList.isEmpty()) {
                // 找第一张图片
                for (MomentsMedia media : mediaList) {
                    if (media.getType() == 1) { // 1=图片
                        task.setFirstMediaUrl(media.getUrl());
                        break;
                    }
                }
            }
            // 计算 contentPreview（保留50字符，保持单词完整性）
            task.setContentPreview(truncateContent(task.getContent(), 50));
            // 计算 isExpired
            task.setIsExpired(isExpired(task));
        }
        TaskListResponse response = new TaskListResponse();
        response.setList(list);
        response.setTotal(total);
        return response;
    }

    /**
     * 截断内容为指定字符数，保持单词完整性
     */
    private String truncateContent(String content, int maxChars) {
        if (content == null) {
            return null;
        }
        // 移除HTML标签
        String text = content.replaceAll("<[^>]+>", "");
        if (text.length() <= maxChars) {
            return text;
        }
        // 在空格处截断，保持单词完整性
        int lastSpace = text.substring(0, maxChars).lastIndexOf(' ');
        if (lastSpace > maxChars * 0.7) { // 如果在70%位置内有空格
            return text.substring(0, lastSpace) + "...";
        }
        return text.substring(0, maxChars) + "...";
    }

    /**
     * 判断任务是否已过期
     * 已过期：scheduledTime < 当前时间 && status = 1 (待执行)
     */
    private Boolean isExpired(MomentsTask task) {
        if (task.getStatus() != null && task.getStatus() == 1 && task.getScheduledTime() != null) {
            return task.getScheduledTime().isBefore(java.time.LocalDateTime.now());
        }
        return false;
    }

    /**
     * 查询任务详情（含媒体文件）
     */
    public MomentsTask getTaskDetail(Long id) {
        MomentsTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ErrorCode.MOMENTS_003);
        }
        // 填充计算字段
        // 计算 firstMediaUrl
        List<MomentsMedia> mediaList = mediaMapper.selectByTaskId(task.getId());
        if (mediaList != null && !mediaList.isEmpty()) {
            for (MomentsMedia media : mediaList) {
                if (media.getType() == 1) { // 1=图片
                    task.setFirstMediaUrl(media.getUrl());
                    break;
                }
            }
        }
        // 计算 contentPreview（保留50字符，保持单词完整性）
        task.setContentPreview(truncateContent(task.getContent(), 50));
        // 计算 isExpired
        task.setIsExpired(isExpired(task));
        return task;
    }

    /**
     * 查询任务的所有媒体文件
     */
    public List<MomentsMedia> getTaskMedia(Long taskId) {
        return mediaMapper.selectByTaskId(taskId);
    }

    /**
     * 根据ID查询媒体文件
     */
    public MomentsMedia getMediaById(Long id) {
        MomentsMedia media = mediaMapper.selectById(id);
        if (media == null) {
            throw new ServiceException(ErrorCode.MOMENTS_004);
        }
        return media;
    }

    /**
     * 创建任务
     */
    @Transactional
    public MomentsTask createTask(CreateTaskRequest request, Long creatorId) {
        WechatAccount account = null;
        // 优先使用accountId查询账号
        if (request.getAccountId() != null) {
            account = accountMapper.selectById(request.getAccountId());
        }
        // 如果accountId不存在，尝试用wechatId查询或自动创建
        if (account == null && request.getWechatId() != null) {
            account = accountMapper.selectByWechatId(request.getWechatId());
            // 如果账号不存在但提供了wechatId和wechatNickname，自动创建账号
            if (account == null && request.getWechatNickname() != null) {
                account = new WechatAccount();
                account.setWechatId(request.getWechatId());
                account.setNickname(request.getWechatNickname());
                account.setStatus(AccountStatus.NORMAL.getCode());
                account.setRemark("自动创建");
                accountMapper.insert(account);
            }
        }
        // 验证账号存在且状态正常
        if (account == null || !account.getStatus().equals(AccountStatus.NORMAL.getCode())) {
            throw new ServiceException(ErrorCode.MOMENTS_002);
        }
        // 创建任务
        MomentsTask task = new MomentsTask();
        task.setAccountId(account.getId());
        task.setAccountNickname(account.getNickname());
        task.setContent(request.getContent());
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setPriority(request.getPriority() != null ? request.getPriority() : 2);
        task.setScheduledTime(request.getScheduledTime());
        task.setCreatorId(creatorId);
        taskMapper.insert(task);
        // 保存媒体文件
        if (request.getMediaUrls() != null && !request.getMediaUrls().isEmpty()) {
            saveMediaFiles(task.getId(), request.getMediaUrls());
        }
        return task;
    }

    /**
     * 批量创建任务
     */
    @Transactional
    public List<MomentsTask> batchCreateTask(BatchCreateTaskRequest request, Long creatorId) {
        // 验证账号存在且状态正常
        WechatAccount account = accountMapper.selectById(request.getAccountId());
        if (account == null || !account.getStatus().equals(AccountStatus.NORMAL.getCode())) {
            throw new ServiceException(ErrorCode.MOMENTS_002);
        }
        // 批量创建任务（使用单独插入以获取正确的ID）
        List<MomentsTask> tasks = new ArrayList<>();
        for (String content : request.getContents()) {
            MomentsTask task = new MomentsTask();
            task.setAccountId(request.getAccountId());
            task.setAccountNickname(account.getNickname());
            task.setContent(content);
            task.setStatus(TaskStatus.PENDING.getCode());
            task.setPriority(request.getPriority() != null ? request.getPriority() : 2);
            task.setScheduledTime(request.getScheduledTime());
            task.setCreatorId(creatorId);
            // 使用单独插入，自动获取生成的ID
            taskMapper.insert(task);
            tasks.add(task);
        }
        // 保存媒体文件（所有任务共用）
        if (request.getMediaUrls() != null && !request.getMediaUrls().isEmpty()) {
            for (MomentsTask task : tasks) {
                saveMediaFiles(task.getId(), request.getMediaUrls());
            }
        }
        return tasks;
    }

    /**
     * 更新任务
     */
    @Transactional
    public void updateTask(Long id, UpdateTaskRequest request) {
        MomentsTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ErrorCode.MOMENTS_003);
        }
        if (request.getContent() != null) {
            task.setContent(request.getContent());
        }
        if (request.getPriority() != null) {
            task.setPriority(request.getPriority());
        }
        if (request.getScheduledTime() != null) {
            task.setScheduledTime(request.getScheduledTime());
        }
        taskMapper.update(task);
    }

    /**
     * 更新任务状态
     */
    public void updateTaskStatus(Long id, Integer status) {
        MomentsTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ErrorCode.MOMENTS_003);
        }
        taskMapper.updateStatus(id, status);
    }

    /**
     * 删除任务
     */
    @Transactional
    public void deleteTask(Long id) {
        MomentsTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ErrorCode.MOMENTS_003);
        }
        // 删除关联的媒体文件
        mediaMapper.deleteByTaskId(id);
        // 删除任务
        taskMapper.deleteById(id);
    }

    /**
     * 重启任务：将状态重置为"待执行"
     * 用于运营人员手动重置任务状态
     */
    public RefreshTaskResponse refreshTaskStatus(Long id) {
        MomentsTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ErrorCode.MOMENTS_003);
        }
        Integer previousStatus = task.getStatus();
        // 已删除的任务不能重启
        if (TaskStatus.DELETED.getCode().equals(previousStatus)) {
            throw new ServiceException(ErrorCode.MOMENTS_006);
        }
        // 重启就是将状态重置为"待执行"
        Integer newStatus = TaskStatus.PENDING.getCode();
        // 如果当前状态已经是PENDING，无需更新
        if (!previousStatus.equals(newStatus)) {
            taskMapper.updateStatus(id, newStatus);
        }
        // 构建响应
        RefreshTaskResponse response = new RefreshTaskResponse();
        response.setTaskId(id);
        response.setPreviousStatus(previousStatus);
        response.setCurrentStatus(newStatus);
        response.setRefreshSuccess(true);
        return response;
    }

    /**
     * 模拟微信API调用获取任务状态
     * 实际项目中需要调用真实的微信API
     */
    private Integer simulateWechatApiCall(Long taskId) {
        // 模拟：根据任务ID生成不同的状态
        // 实际项目中应该调用微信服务器API获取真实状态
        MomentsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return TaskStatus.FAILED.getCode();
        }
        // 模拟逻辑：返回SUCCESS表示刷新成功
        return TaskStatus.SUCCESS.getCode();
    }

    /**
     * 保存媒体文件
     */
    private void saveMediaFiles(Long taskId, List<String> urls) {
        List<MomentsMedia> mediaList = new ArrayList<>();
        int sortOrder = 0;
        for (String url : urls) {
            MomentsMedia media = new MomentsMedia();
            media.setTaskId(taskId);
            // 根据URL后缀判断类型
            if (url.toLowerCase().endsWith(".mp4") || url.toLowerCase().endsWith(".avi")) {
                media.setType(2);
            } else {
                media.setType(1);
            }
            media.setUrl(url);
            media.setOriginalName(url.substring(url.lastIndexOf('/') + 1));
            media.setSortOrder(sortOrder++);
            mediaList.add(media);
        }
        if (!mediaList.isEmpty()) {
            mediaMapper.batchInsert(mediaList);
        }
    }

    /**
     * 业务异常类
     */
    public static class ServiceException extends RuntimeException {
        private final ErrorCode errorCode;
        public ServiceException(ErrorCode errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }
        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}