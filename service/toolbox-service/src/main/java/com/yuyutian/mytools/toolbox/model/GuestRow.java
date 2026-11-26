package com.yuyutian.mytools.toolbox.model;

import java.time.Instant;

/**
 * 匿名访客会话持久化行；服务端只保存 token 的 SHA-256。
 *
 * @param ownerId 公开命名空间自增主键
 * @param guestId 访客标识
 * @param tokenHash 会话 token 的 SHA-256
 * @param createdAt 创建时间
 * @param expiresAt 到期时间
 * @param revokedAt 撤销时间，可为空
 */
public record GuestRow(
        /** 公开命名空间自增主键。 */
        long ownerId,
        /** 访客标识。 */
        String guestId,
        /** 会话 token 的 SHA-256。 */
        byte[] tokenHash,
        /** 创建时间。 */
        Instant createdAt,
        /** 到期时间。 */
        Instant expiresAt,
        /** 撤销时间，可为空。 */
        Instant revokedAt) {
}
