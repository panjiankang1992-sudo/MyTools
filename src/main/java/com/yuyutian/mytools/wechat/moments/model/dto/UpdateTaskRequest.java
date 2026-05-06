package com.yuyutian.mytools.wechat.moments.model.dto;

import lombok.Data;

/**
 * 更新朋友圈任务请求
 */
@Data
public class UpdateTaskRequest {
    /** 任务内容（富文本HTML） */
    private String content;
    /** 优先级: 1-低, 2-中, 3-高 */
    private Integer priority;
    /** 定时发布时间 */
    private java.time.LocalDateTime scheduledTime;
}
