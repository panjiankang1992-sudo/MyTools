package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Drive Gateway 数据模型。
 */
public final class DriveGatewayModels {
    private DriveGatewayModels() { }

    public record RefreshIndexRequest(@NotBlank @Size(max = 255) String idempotencyKey) { }
    public record OperationView(UUID id, UUID accountId, UUID taskInstanceId, String operationType,
                                String status, String errorCode, Instant createdAt, Instant updatedAt) { }
}
