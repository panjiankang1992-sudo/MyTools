package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 记录无副作用投递影子的请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param channelType 渠道类型
 * @param recipientHash 收件目标摘要
 * @param payloadHash 投递载荷摘要
 * @param legacyOutcome 旧链路结果
 */
public record CreateDeliveryShadowRequest(
        @NotNull Long ownerId,
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotNull ChannelType channelType,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String recipientHash,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String payloadHash,
        @NotBlank @Size(max = 32) String legacyOutcome) {
}
