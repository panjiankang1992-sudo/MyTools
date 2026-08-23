package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Executor 分批写入电子书目录的内部请求。
 *
 * @param replace 是否先清空本次导入已有目录
 * @param entries 目录条目
 */
public record CatalogBatchRequest(boolean replace,
                                  @NotEmpty @Size(max = 200) List<@Valid CatalogEntry> entries) {
    /**
     * 单个电子书目录条目。
     *
     * @param index 顺序索引
     * @param title 标题
     * @param resourceRef 格式相关资源引用
     * @param startOffset 可选开始字节位置
     * @param endOffset 可选结束字节位置
     */
    public record CatalogEntry(@Min(0) @Max(19999) int index,
                               @NotBlank @Size(max = 500) String title,
                               @NotBlank @Size(max = 4096) String resourceRef,
                               @Min(0) Long startOffset,
                               @Min(0) Long endOffset) {
    }
}
