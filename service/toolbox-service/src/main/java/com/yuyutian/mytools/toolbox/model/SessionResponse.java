package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * 会话接口响应：只返回到期时间与会话绑定的 CSRF token，原始会话 token 仅经 cookie 下发。
 *
 * @param expiresAt 会话到期时间
 * @param csrfToken 变更接口必须回带的 CSRF token
 */
public record SessionResponse(
        /** 会话到期时间。 */
        @JsonProperty("expiresAt") Instant expiresAt,
        /** 变更接口必须回带的 CSRF token。 */
        @JsonProperty("csrfToken") String csrfToken) {
}
