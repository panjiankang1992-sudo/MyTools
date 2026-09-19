package com.yuyutian.mytools.auth.messaging;

import java.time.LocalDateTime;

/**
 * 注册邮件真实投递 Outbox 记录。
 */
public record RegistrationMailDeliveryOutbox(Long verificationId, String idempotencyKey,
                                             String encryptedPayload, String nonce,
                                             String status, Integer attemptCount,
                                             LocalDateTime availableAt, LocalDateTime claimedUntil,
                                             String lastErrorCode, LocalDateTime createdAt,
                                             LocalDateTime updatedAt) {
}
