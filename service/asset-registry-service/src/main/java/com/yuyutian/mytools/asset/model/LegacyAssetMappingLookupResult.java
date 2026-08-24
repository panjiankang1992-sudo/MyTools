package com.yuyutian.mytools.asset.model;

import java.util.List;
import java.util.UUID;

/**
 * 旧资产映射批量查询结果。
 */
public record LegacyAssetMappingLookupResult(List<Mapping> mappings,
                                             List<LegacyAssetMappingLookupRequest.Identity> missing) {

    /**
     * 已存在的不可变旧资产到新资产引用。
     */
    public record Mapping(String sourceSystem, String legacyAssetId, UUID assetId) {
    }
}
