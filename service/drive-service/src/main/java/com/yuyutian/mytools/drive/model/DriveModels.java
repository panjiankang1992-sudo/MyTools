package com.yuyutian.mytools.drive.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** Drive API 数据模型。 */
public final class DriveModels {
    private DriveModels() { }
    public record RegisterAccountRequest(@NotNull Long ownerId, @NotBlank @Size(max=255) String externalAccountId,
        @NotBlank @Size(max=255) String displayName, @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String providerType,
        @NotBlank @Size(max=512) String providerSecretRef, @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,128}$") String remoteKey,
        boolean readOnly, boolean enabled) { }
    public record AccountView(UUID id, long ownerId, String externalAccountId, String displayName,
        String providerType, String remoteKey, boolean readOnly, boolean enabled, long indexGeneration) { }
    public record BindStorageProviderRequest(@NotNull UUID storageProviderId) { }
    public record StorageMigrationAccount(UUID id, String remoteKey, String providerSecretRef, boolean enabled) { }
    public record StorageMigrationPage(List<StorageMigrationAccount> items, UUID nextAfterId) { }
    public record LegacyAccountMigrationItem(
        @NotBlank @Pattern(regexp="^(DRIVE|WEBDAV)$") String sourceSystem,
        @Positive long legacyAccountId,
        @NotNull @Valid RegisterAccountRequest account) { }
    public record LegacyAccountMigrationBatch(
        @NotBlank @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") String migrationKey,
        boolean dryRun,
        @NotNull @Size(max=100) List<@Valid LegacyAccountMigrationItem> items) { }
    public record LegacyAccountMigrationResult(String migrationKey, boolean dryRun, int exported,
        int accepted, int skipped, int rejected, String digestSha256) { }
    public record LegacyAccountMigrationEvidence(String migrationKey, long itemCount, String digestSha256) { }
    public record IndexDigest(long itemCount, String contentSha256) { }
    public record IndexItem(@Size(max=255) String remoteId, @NotBlank @Size(max=2048) String remotePath,
        @NotNull @Size(max=2048) String parentPath, @NotBlank @Size(max=512) String displayName,
        @Size(max=255) String mimeType, @PositiveOrZero long sizeBytes, boolean directory, Instant modifiedAt,
        @Pattern(regexp="^[a-fA-F0-9]{64}$") String contentSha256) { }
    public record IndexBatchRequest(@NotNull UUID runId, @NotBlank @Size(max=255) String batchKey,
        @Size(max=2048) String nextCursor, boolean complete, @NotNull @Size(max=1000) List<@Valid IndexItem> items) { }
    public record IndexBatchView(UUID runId, long generation, String nextCursor, String status, int acceptedItems) { }
    public record ItemView(UUID id, String remoteId, String remotePath, String parentPath, String displayName,
        String mimeType, long sizeBytes, boolean directory, Instant modifiedAt, String contentSha256) { }
    public record ItemContent(InputStream stream, long contentLength, String displayName, String mimeType,
                              int statusCode, String contentRange, String acceptRanges) {
        /** 创建完整文件流。 @param stream 流 @param contentLength 长度 @param displayName 名称 @param mimeType 类型 */
        public ItemContent(InputStream stream, long contentLength, String displayName, String mimeType) {
            this(stream, contentLength, displayName, mimeType, 200, null, null);
        }
    }
    public record RefreshIndexRequest(@NotBlank @Size(max=255) String idempotencyKey) { }
    public record CopyObjectRequest(@NotBlank @Size(max=255) String idempotencyKey,
        @NotNull UUID targetAccountId, @NotBlank @Size(max=2048) String sourcePath,
        @NotBlank @Size(max=2048) String targetPath) { }
    public record CopyTreeRequest(@NotBlank @Size(max=255) String idempotencyKey,
        @NotNull UUID targetAccountId, @Size(max=2048) String sourcePath,
        @Size(max=2048) String targetPath, @Min(1) @Max(1000000) int maximumObjects) { }
    public record MoveTreeRequest(@NotBlank @Size(max=255) String idempotencyKey,
        @NotNull UUID targetAccountId, @NotBlank @Size(max=2048) String sourcePath,
        @NotBlank @Size(max=2048) String targetPath, @Min(1) @Max(1000000) int maximumObjects) { }
    public record DeleteTreeRequest(@NotBlank @Size(max=255) String idempotencyKey,
        @NotBlank @Size(max=2048) String path, @Min(1) @Max(1000000) int maximumObjects) { }
    public record StorageOperationView(UUID id, UUID taskInstanceId, String operationType,
        String status, String errorCode) { }
    public record OperationView(UUID id, UUID accountId, UUID taskInstanceId, String operationType,
        String status, String errorCode, Instant createdAt, Instant updatedAt) { }
}
