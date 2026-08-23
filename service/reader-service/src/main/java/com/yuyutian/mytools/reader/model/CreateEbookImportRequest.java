package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 创建书源电子书导入请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param sourceId 新 schema 书源标识
 * @param bookUrl 书源图书地址
 * @param title 回退标题
 * @param author 回退作者
 */
public record CreateEbookImportRequest(@NotNull Long ownerId,
                                       @NotBlank @Size(max = 255) String idempotencyKey,
                                       @NotNull UUID sourceId,
                                       @NotBlank @Size(max = 4096) String bookUrl,
                                       @NotBlank @Size(max = 300) String title,
                                       @Size(max = 200) String author) {
}
