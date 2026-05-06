package com.yuyutian.mytools.wechat.moments.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 朋友圈任务实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MomentsTask {
    /** 任务ID */
    private Long id;
    /** 关联的微信账号ID */
    private Long accountId;
    /** 微信账号昵称（冗余存储） */
    private String accountNickname;
    /** 任务内容（富文本HTML） */
    private String content;
    /** 任务状态: 1-待执行, 2-执行中, 3-已完成, 4-已取消 */
    private Integer status;
    /** 优先级: 1-低, 2-中, 3-高 */
    private Integer priority;
    /** 定时发布时间（为空则立即发布） */
    private LocalDateTime scheduledTime;
    /** 实际发布时间 */
    private LocalDateTime publishTime;
    /** 创建用户ID */
    private Long creatorId;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;

    // ========== 计算字段（不存储在数据库，返回列表时填充）==========

    /** 第一张图片URL（缩略图用） */
    private String firstMediaUrl;

    /** 内容预览（前50字符，保持单词完整性） */
    private String contentPreview;

    /** 是否已过期（计算得出：scheduledTime < NOW && status = 1） */
    private Boolean isExpired;
}