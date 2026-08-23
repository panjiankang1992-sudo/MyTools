package com.yuyutian.mytools.drive.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
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
    public record IndexItem(@Size(max=255) String remoteId, @NotBlank @Size(max=2048) String remotePath,
        @NotBlank @Size(max=2048) String parentPath, @NotBlank @Size(max=512) String displayName,
        @Size(max=255) String mimeType, @PositiveOrZero long sizeBytes, boolean directory, Instant modifiedAt,
        @Pattern(regexp="^[a-fA-F0-9]{64}$") String contentSha256) { }
    public record IndexBatchRequest(@NotNull UUID runId, @NotBlank @Size(max=255) String batchKey,
        @Size(max=2048) String nextCursor, boolean complete, @NotNull @Size(max=1000) List<@Valid IndexItem> items) { }
    public record IndexBatchView(UUID runId, long generation, String nextCursor, String status, int acceptedItems) { }
    public record ItemView(UUID id, String remoteId, String remotePath, String parentPath, String displayName,
        String mimeType, long sizeBytes, boolean directory, Instant modifiedAt, String contentSha256) { }
}
