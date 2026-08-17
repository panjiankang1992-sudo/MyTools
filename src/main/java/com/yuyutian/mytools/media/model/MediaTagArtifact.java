package com.yuyutian.mytools.media.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 可在 DownloadBot 与 MyTools 之间复用的标签产物。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaTagArtifact(
        int schemaVersion,
        String status,
        String contentSha256,
        String producer,
        String provider,
        String model,
        String promptVersion,
        String inputKind,
        String inputFingerprint,
        String generatedAt,
        List<Tag> tags) {

    /**
     * 单个规范化标签。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String name, String type, double confidence) {
    }
}
