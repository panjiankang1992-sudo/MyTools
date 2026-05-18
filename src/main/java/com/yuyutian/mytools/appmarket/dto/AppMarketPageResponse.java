package com.yuyutian.mytools.appmarket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 应用分页响应DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppMarketPageResponse {

    /** 应用列表 */
    private List<AppMarketListResponse> list;

    /** 总数 */
    private Long total;

    /** 当前页码 */
    private Integer page;

    /** 每页数量 */
    private Integer pageSize;
}
