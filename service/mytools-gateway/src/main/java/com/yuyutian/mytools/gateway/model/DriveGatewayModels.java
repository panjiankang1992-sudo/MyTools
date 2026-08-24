package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Drive Gateway 数据模型。
 */
public final class DriveGatewayModels {
    private DriveGatewayModels() { }

    public record RefreshIndexRequest(@NotBlank @Size(max = 255) String idempotencyKey) { }
    public record CopyObjectRequest(@NotBlank @Size(max = 255) String idempotencyKey,
                                    @NotNull UUID targetAccountId,
                                    @NotBlank @Size(max = 2048) String sourcePath,
                                    @NotBlank @Size(max = 2048) String targetPath) { }
    public record AccountSummary(UUID id, String displayName, String providerType, boolean readOnly,
                                 boolean enabled, long indexGeneration) { }
    public record OperationView(UUID id, UUID accountId, String operationType,
                                String status, String errorCode, Instant createdAt, Instant updatedAt) { }
}
