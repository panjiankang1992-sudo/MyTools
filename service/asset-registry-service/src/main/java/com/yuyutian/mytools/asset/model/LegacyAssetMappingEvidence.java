package com.yuyutian.mytools.asset.model;

/**
 * 已提交旧资产映射的集合证据。
 *
 * @param migrationKey 迁移键
 * @param sourceSnapshotId 来源快照标识
 * @param itemCount 映射数量
 * @param collectionSha256 映射载荷集合摘要
 */
public record LegacyAssetMappingEvidence(String migrationKey, String sourceSnapshotId,
                                         long itemCount, String collectionSha256) {
}
