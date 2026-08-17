package com.yuyutian.mytools.media.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DownloadBot 发布的大视频资源包清单。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaPackageManifest(
        int schemaVersion,
        String packageId,
        String packageStatus,
        String sourceType,
        Long sourceAssetId,
        String sourceEventKey,
        String originalFileName,
        String videoFile,
        String contentSha256,
        long sizeBytes,
        String mimeType,
        String storagePolicyVersion,
        String tagStatus,
        String tagArtifact,
        String createdAt,
        String updatedAt) {
}
