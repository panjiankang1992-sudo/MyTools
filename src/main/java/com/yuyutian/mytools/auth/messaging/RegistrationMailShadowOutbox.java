package com.yuyutian.mytools.auth.messaging;

import java.time.LocalDateTime;

/**
 * 注册邮件影子 Outbox 记录。
 *
 * @param verificationId 验证码记录标识
 * @param idempotencyKey 幂等键
 * @param recipientHmac 收件人 HMAC
 * @param payloadHmac 载荷 HMAC
 * @param legacyOutcome 旧链路结果
 * @param status 状态
 * @param attemptCount 尝试次数
 * @param availableAt 下次可投递时间
 * @param claimedUntil 领取截止时间
 * @param lastErrorCode 最近错误码
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RegistrationMailShadowOutbox(Long verificationId, String idempotencyKey,
                                            String recipientHmac, String payloadHmac,
                                            String legacyOutcome, String status, Integer attemptCount,
                                            LocalDateTime availableAt, LocalDateTime claimedUntil,
                                            String lastErrorCode, LocalDateTime createdAt,
                                            LocalDateTime updatedAt) {
}
