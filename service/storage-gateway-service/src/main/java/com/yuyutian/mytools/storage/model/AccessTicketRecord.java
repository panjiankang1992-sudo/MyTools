package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 不包含原始 Token 的访问票据持久化模型。
 *
 * @param id 标识
 * @param tokenSha256 Token 摘要
 * @param rootId 根标识
 * @param rootName 根名称
 * @param relativePath 相对路径
 * @param permission 权限
 * @param expiresAt 到期时间
 * @param consumedAt 消费时间
 * @param revokedAt 撤销时间
 * @param createdAt 创建时间
 */
public record AccessTicketRecord(UUID id, String tokenSha256, UUID rootId, String rootName,
                                 String relativePath, String permission, Instant expiresAt,
                                 Instant consumedAt, Instant revokedAt, Instant createdAt) {
}
