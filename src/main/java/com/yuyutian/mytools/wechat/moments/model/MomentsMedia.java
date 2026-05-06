package com.yuyutian.mytools.wechat.moments.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 朋友圈多媒体文件实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MomentsMedia {
    /** 文件ID */
    private Long id;
    /** 关联的任务ID */
    private Long taskId;
    /** 文件类型: 1-图片, 2-视频 */
    private Integer type;
    /** 文件URL/路径 */
    private String url;
    /** 原始文件名 */
    private String originalName;
    /** 文件大小（字节） */
    private Long size;
    /** 排序序号 */
    private Integer sortOrder;
    /** 创建时间 */
    private LocalDateTime createTime;
}