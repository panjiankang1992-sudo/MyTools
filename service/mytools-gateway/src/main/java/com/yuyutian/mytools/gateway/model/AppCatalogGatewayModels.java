package com.yuyutian.mytools.gateway.model;

import java.util.UUID;

/**
 * 应用目录 Gateway 模型。
 */
public final class AppCatalogGatewayModels {
    private AppCatalogGatewayModels() {
    }

    /**
     * 应用目录摘要。
     */
    public record CatalogView(UUID id, String legacyId, long ownerId, String name, String appType,
                              String currentVersion, String status, int versionCount, int fileCount) {
    }
}
