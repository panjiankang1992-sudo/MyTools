package com.yuyutian.mytools.wechat.moments.model.dto;

import lombok.Data;
import java.util.List;

/**
 * 批量创建朋友圈任务请求
 */
@Data
public class BatchCreateTaskRequest {
    /** 关联的微信账号ID */
    private Long accountId;
    /** 任务内容列表（批量创建） */
    private List<String> contents;
    /** 优先级: 1-低, 2-中, 3-高 */
    private Integer priority;
    /** 定时发布时间（为空则立即发布） */
    private java.time.LocalDateTime scheduledTime;
    /** 媒体文件URL列表（每个任务共用） */
    private List<String> mediaUrls;
}