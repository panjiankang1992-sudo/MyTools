package com.yuyutian.mytools.pikpak.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PikPak 连接器 API 与持久化模型。 */
public final class PikPakModels {
    private PikPakModels() { }

    public record RegisterAccountRequest(@NotBlank @Size(max=128) String externalKey,
        @NotNull UUID storageProviderId, @NotBlank @Pattern(regexp="^secret://.+") String secretRef,
        @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,128}$") String remoteKey,
        @NotBlank @Size(max=512) String offlineRoot, @NotBlank @Size(max=512) String readyRoot,
        boolean enabled) { }
    public record Account(UUID id, String externalKey, UUID storageProviderId, String secretRef,
        String remoteKey, String offlineRoot, String readyRoot, boolean enabled) { }
    public record AccountView(UUID id, String externalKey, UUID storageProviderId, boolean enabled) { }
    public record CreateOperationRequest(@NotNull UUID accountId, @NotBlank @Size(max=8192) String magnetUri,
        @NotBlank @Size(max=255) String idempotencyKey, @NotBlank @Size(max=64) String businessType,
        @NotBlank @Size(max=128) String businessId) { }
    public record Operation(UUID id, UUID accountId, String idempotencyKey, String businessType,
        String businessId, String inputSha256, String workToken, String phase, String stableSignature,
        Instant stableSince, Long remoteJobId, String errorCode, long version) { }
    public record RemoteItem(String remoteFileId, String relativePath, long sizeBytes, String modifiedAt) { }
    public record ReadyItem(String remoteFileId, String relativePath, long sizeBytes,
        UUID storageProviderId, String storagePath) { }
    public record OperationView(UUID id, String phase, String errorCode, int retryAfterSeconds,
        List<ReadyItem> items) { }
    public record RemoteJob(long id, boolean finished, boolean success) { }
}
