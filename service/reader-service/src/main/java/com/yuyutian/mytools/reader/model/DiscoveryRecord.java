package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 书源发现持久化记录。
 *
 * @param id 请求标识
 * @param ownerId 所有者标识
 * @param idempotencyKey 幂等键
 * @param url 仓库地址
 * @param status 状态
 * @param taskId 任务标识
 * @param processed 已处理数量
 * @param saved 已保存数量
 * @param rejected 已拒绝数量
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DiscoveryRecord(UUID id, long ownerId, String idempotencyKey, String url, String status,
                              UUID taskId, int processed, int saved, int rejected,
                              Instant createdAt, Instant updatedAt) {
}
