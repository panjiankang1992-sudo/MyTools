package com.yuyutian.mytools.reader.model;

import java.util.List;

/**
 * 电子书目录分页结果。
 *
 * @param list 当前页数据
 * @param total 总数
 * @param page 页码
 * @param pageSize 每页数量
 */
public record EbookCatalogPage(List<EbookCatalogItem> list, long total, long page, long pageSize) {
}
