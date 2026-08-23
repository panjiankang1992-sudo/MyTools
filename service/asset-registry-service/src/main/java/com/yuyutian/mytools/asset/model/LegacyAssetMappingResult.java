package com.yuyutian.mytools.asset.model;

/**
 * 旧资产映射迁移批次报告。
 */
public record LegacyAssetMappingResult(boolean dryRun, int accepted, int skipped, int rejected,
                                       String digestSha256) {
}
