package com.yuyutian.mytools.asset.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 已发布且不可变的资产资源包。
 */
public record AssetBundleView(UUID id, long ownerId, String name, String description,
                              String manifestSha256, String status, List<ItemView> items,
                              Instant createdAt, Instant publishedAt) {

    /**
     * 返回不可变资源项集合。
     *
     * @return 资源项
     */
    @Override
    public List<ItemView> items() {
        return List.copyOf(items);
    }

    /**
     * 资源包资产快照。
     */
    public record ItemView(UUID assetId, long assetVersion, String logicalPath, String role,
                           int sequenceNumber) {
    }
}
