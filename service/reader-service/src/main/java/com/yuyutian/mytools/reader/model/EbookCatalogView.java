package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 已完成电子书导入的同步目录视图。
 *
 * @param importRequestId 导入请求标识
 * @param entries 有序目录条目
 */
public record EbookCatalogView(UUID importRequestId, List<Entry> entries) {
    /**
     * 单个目录条目。
     *
     * @param index 条目索引
     * @param title 标题
     * @param resourceRef 格式相关资源引用
     */
    public record Entry(int index, String title, String resourceRef) {
    }
}
