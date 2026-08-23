package com.yuyutian.mytools.asset.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 资产及其来源、位置和派生关系视图。
 */
public record AssetView(UUID id, String contentSha256, long sizeBytes, String mimeType,
                        String status, long version, List<SourceView> sources,
                        List<LocationView> locations, List<ArtifactView> artifacts,
                        Instant createdAt, Instant updatedAt) {

    /**
     * 资产业务来源。
     */
    public record SourceView(long ownerId, String sourceType, String sourceBusinessId, String eventKey) {
    }

    /**
     * 资产存储位置。
     */
    public record LocationView(UUID id, String providerType, String storageUri, String providerVersion,
                               String availability, String invalidationReason, Instant invalidatedAt) {
    }

    /**
     * 资产派生关系。
     */
    public record ArtifactView(UUID artifactAssetId, String artifactKind, String generatorName,
                               String generatorVersion) {
    }
}
