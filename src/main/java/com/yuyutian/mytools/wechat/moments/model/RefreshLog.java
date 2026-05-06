package com.yuyutian.mytools.wechat.moments.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 刷新日志实体。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class RefreshLog {

    /** 主键ID */
    private Long id;

    /** 账号ID */
    private Long accountId;

    /** 操作类型：0-刷新任务列表，1-发布朋友圈，2-手动刷新，3-自动刷新 */
    private Integer operateType;

    /** 成功数量 */
    private Integer successCount;

    /** 失败数量 */
    private Integer failCount;

    /** 操作时间 */
    private LocalDateTime operateTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
