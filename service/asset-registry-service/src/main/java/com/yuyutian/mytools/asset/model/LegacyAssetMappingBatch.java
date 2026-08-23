package com.yuyutian.mytools.asset.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 有界旧资产映射迁移批次。
 */
public record LegacyAssetMappingBatch(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String migrationKey,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String sourceSnapshotId,
        boolean dryRun,
        @NotEmpty @Size(max = 200) List<@Valid LegacyAssetMappingItem> items) {

    /**
     * 返回不可变迁移项集合。
     *
     * @return 迁移项
     */
    @Override
    public List<LegacyAssetMappingItem> items() {
        return items == null ? List.of() : List.copyOf(items);
    }
}
