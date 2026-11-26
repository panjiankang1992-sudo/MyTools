package com.yuyutian.mytools.toolbox.model;

import java.time.Instant;

/**
 * 已校验的匿名会话视图，只用于服务端内部处理。
 *
 * @param ownerId 公开命名空间自增主键
 * @param guestId 访客标识
 * @param expiresAt 到期时间
 * @param csrfToken 会话绑定的 CSRF token
 */
public record GuestSession(
        /** 公开命名空间自增主键。 */
        long ownerId,
        /** 访客标识。 */
        String guestId,
        /** 到期时间。 */
        Instant expiresAt,
        /** 会话绑定的 CSRF token。 */
        String csrfToken) {
}
