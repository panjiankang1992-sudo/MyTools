package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 电子书导入视图。
 *
 * @param id 请求标识
 * @param taskId 调度任务标识
 * @param status 状态
 * @param sourceId 书源标识
 * @param sourceVersion 使用的书源版本
 * @param title 标题
 * @param author 作者
 * @param chapterCount 章节数
 * @param outputSize 输出字节数
 * @param sha256 输出摘要
 * @param storageUri 存储 URI
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record EbookImportView(UUID id, UUID taskId, String status, UUID sourceId, int sourceVersion,
                              String title, String author, Integer chapterCount, Long outputSize,
                              String sha256, String storageUri, Instant createdAt, Instant updatedAt) {
}
