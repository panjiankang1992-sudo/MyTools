package com.yuyutian.mytools.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果封装类。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 总记录数 */
    private Long total;

    /** 当前页码 */
    private Integer page;

    /** 每页记录数 */
    private Integer pageSize;

    /** 数据列表 */
    private List<T> list;
}
