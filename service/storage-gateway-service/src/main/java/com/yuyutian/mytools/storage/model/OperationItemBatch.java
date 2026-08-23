package com.yuyutian.mytools.storage.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 扫描任务回写的对象批次。
 *
 * @param items 标准化对象
 */
public record OperationItemBatch(@NotEmpty @Size(max = 500) List<@Valid RemoteObjectView> items) {
}
