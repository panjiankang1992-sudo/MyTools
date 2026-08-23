package com.yuyutian.mytools.asset.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 按内容和业务来源幂等登记资产。
 */
public record RegisterAssetRequest(
        @NotNull Long ownerId,
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String sourceType,
        @NotBlank @Size(max = 255) String sourceBusinessId,
        @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String contentSha256,
        @Positive long sizeBytes,
        @NotBlank @Size(max = 255) String mimeType,
        @Valid InitialLocation location) {

    /**
     * 与首次来源一起登记的可选存储位置。
     */
    public record InitialLocation(
            @NotBlank @Size(max = 255) String idempotencyKey,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String providerType,
            @NotBlank @Size(max = 4096) String storageUri,
            @Size(max = 255) String providerVersion) {
    }
}
