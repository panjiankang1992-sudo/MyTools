package com.yuyutian.mytools.wechat.account.model.dto;

import lombok.Data;

/**
 * 刷新结果响应。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class RefreshResultResponse {

    /** 成功数量 */
    private Integer successCount;

    /** 失败数量 */
    private Integer failCount;

    /** 总数量 */
    private Integer totalCount;
}
