package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Gateway 对外暴露的下载稳定契约。
 */
public final class DownloadGatewayModels {
    private DownloadGatewayModels() {
    }

    /**
     * 创建 HTTP 下载请求。
     */
    public record CreateHttpDownload(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String idempotencyKey,
            @NotBlank @Size(max = 8192) String url,
            @NotBlank @Size(max = 255) String fileName,
            @Min(1) @Max(21_474_836_480L) Long maxBytes) {
    }

    /**
     * 不包含下载参数和源地址的请求视图。
     */
    public record DownloadView(UUID id, String status, String createdAt, String updatedAt) {
    }

    /**
     * 下载结果项的安全视图。
     */
    public record ResultItem(String itemId, String fileName, String contentSha256,
                             Long sizeBytes, String storageUri, UUID assetId) {
    }

    /**
     * 下载结果汇总的安全视图。
     */
    public record ResultSummary(UUID downloadRequestId, String status, Integer itemCount,
                                Long totalBytes, String collectionSha256,
                                String contentSetSha256, List<ResultItem> items) {
    }
}
