package com.yuyutian.mytools.wechat.moments.model.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 创建朋友圈任务请求
 */
@Data
public class CreateTaskRequest {
    /** 关联的微信账号ID（优先使用） */
    private Long accountId;
    /** 微信ID/微信号（当accountId不存在时，可配合wechatNickname自动创建账号） */
    private String wechatId;
    /** 微信昵称（当accountId不存在且提供wechatId时，用于自动创建账号） */
    private String wechatNickname;
    /** 任务内容（富文本HTML） */
    private String content;
    /** 优先级: 1-低, 2-中, 3-高 */
    private Integer priority;
    /** 定时发布时间（为空则立即发布） */
    private LocalDateTime scheduledTime;
    /** 媒体文件URL列表 */
    private java.util.List<String> mediaUrls;
}