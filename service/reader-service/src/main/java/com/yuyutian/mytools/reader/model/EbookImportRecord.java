package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 电子书导入持久化记录。
 *
 * @param id 请求标识
 * @param ownerId 所有者标识
 * @param idempotencyKey 幂等键
 * @param sourceId 书源标识
 * @param sourceVersion 书源版本
 * @param bookUrl 图书地址
 * @param title 请求标题
 * @param author 请求作者
 * @param storageRoot 存储根名称
 * @param status 状态
 * @param taskId 任务标识
 * @param parameters 调度参数
 * @param storageUri 输出 URI
 * @param outputSize 输出字节数
 * @param outputSha256 输出摘要
 * @param chapterCount 章节数
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record EbookImportRecord(UUID id, long ownerId, String idempotencyKey, UUID sourceId,
                                int sourceVersion, String bookUrl, String title, String author,
                                String storageRoot, String status, UUID taskId, Map<String, Object> parameters,
                                String storageUri, Long outputSize, String outputSha256, Integer chapterCount,
                                Instant createdAt, Instant updatedAt) {
}
