package com.yuyutian.mytools.wechat.moments.model.dto;

import lombok.Data;

/**
 * 刷新任务状态响应。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class RefreshTaskResponse {

    /** 任务ID */
    private Long taskId;

    /** 之前的状态 */
    private Integer previousStatus;

    /** 当前状态 */
    private Integer currentStatus;

    /** 刷新是否成功 */
    private Boolean refreshSuccess;
}
