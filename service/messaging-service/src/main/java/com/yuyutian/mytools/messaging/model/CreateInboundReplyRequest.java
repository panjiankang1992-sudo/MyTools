package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建入站消息回复请求。
 */
public record CreateInboundReplyRequest(@NotBlank @Size(max = 255) String idempotencyKey,
                                        @NotBlank @Size(max = 10_485_760) String body) {
}
