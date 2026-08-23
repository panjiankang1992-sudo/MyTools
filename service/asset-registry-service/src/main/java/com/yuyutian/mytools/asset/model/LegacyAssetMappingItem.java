package com.yuyutian.mytools.asset.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 旧资产身份及其标准资产登记载荷。
 *
 * @param sourceSystem 旧来源系统
 * @param legacyAssetId 旧资产标识
 * @param asset 标准资产登记请求
 */
public record LegacyAssetMappingItem(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sourceSystem,
        @NotBlank @Size(max = 255) String legacyAssetId,
        @NotNull @Valid RegisterAssetRequest asset) {
}
