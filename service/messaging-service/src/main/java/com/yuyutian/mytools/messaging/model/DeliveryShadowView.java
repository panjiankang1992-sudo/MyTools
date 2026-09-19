package com.yuyutian.mytools.messaging.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 无副作用投递影子视图。
 *
 * @param id 影子记录标识
 * @param idempotencyKey 业务幂等键
 * @param channelType 渠道类型
 * @param legacyOutcome 旧链路结果
 * @param replayed 是否为幂等重放
 * @param createdAt 创建时间
 */
public record DeliveryShadowView(UUID id, String idempotencyKey, ChannelType channelType,
                                 String legacyOutcome, boolean replayed, Instant createdAt) {
}
