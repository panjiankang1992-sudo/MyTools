package com.yuyutian.mytools.messaging.model;

import java.util.UUID;

/**
 * Executor 调用投递接口的最小结果。
 */
public record ExecuteDeliveryResult(UUID deliveryId, String status, String providerMessageId) {
}
