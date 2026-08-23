package com.yuyutian.mytools.automation.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 自动化子动作执行视图。
 */
public record AutomationActionView(UUID id, UUID runId, int sequence, String actionType,
                                   UUID externalRequestId, String status, String errorCode,
                                   Instant createdAt, Instant updatedAt) {
}
