package com.yuyutian.mytools.asset.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 登记或确认资产存储位置。
 */
public record RegisterLocationRequest(@Positive long expectedAssetVersion,
                                      @NotBlank @Size(max = 255) String idempotencyKey,
                                      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String providerType,
                                      @NotBlank @Size(max = 4096) String storageUri,
                                      @Size(max = 255) String providerVersion) {
}
