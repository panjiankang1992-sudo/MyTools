package com.yuyutian.mytools.asset.model;

/**
 * 有界资产注册表对账页，仅返回数量和确定性摘要。
 */
public record AssetReconciliationPage(String nextAfterId, long registryRevision,
                                      int assetCount, int sourceCount,
                                      int availableLocationCount, int invalidLocationCount,
                                      int artifactCount, int bundleReferenceCount,
                                      int legacyMappingCount,
                                      String digestSha256) {
}
