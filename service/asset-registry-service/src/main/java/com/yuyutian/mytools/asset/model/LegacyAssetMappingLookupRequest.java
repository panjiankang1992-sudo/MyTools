package com.yuyutian.mytools.asset.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量查询旧资产映射的有界请求。
 */
public record LegacyAssetMappingLookupRequest(
        @NotEmpty @Size(max = 200) List<@Valid Identity> identities) {

    /**
     * 不包含旧资产载荷的来源身份。
     */
    public record Identity(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sourceSystem,
            @NotBlank @Size(max = 255) String legacyAssetId) {
    }
}
