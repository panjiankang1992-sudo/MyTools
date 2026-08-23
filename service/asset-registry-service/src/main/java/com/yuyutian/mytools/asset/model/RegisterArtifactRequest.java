package com.yuyutian.mytools.asset.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 登记派生资产关系。
 */
public record RegisterArtifactRequest(@Positive long expectedAssetVersion,
                                      @NotNull UUID artifactAssetId,
                                      @NotBlank @Size(max = 255) String idempotencyKey,
                                      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String artifactKind,
                                      @NotBlank @Size(max = 128) String generatorName,
                                      @NotBlank @Size(max = 64) String generatorVersion) {
}
