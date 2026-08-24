package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gateway 对外电子书导入模型。
 */
public final class EbookImportGatewayModels {

    private EbookImportGatewayModels() {
    }

    /**
     * 创建电子书导入请求。
     */
    public record CreateImport(@NotBlank @Size(max = 255) String idempotencyKey,
                               @NotNull UUID sourceId,
                               @NotBlank @Size(max = 4096) String bookUrl,
                               @NotBlank @Size(max = 300) String title,
                               @Size(max = 200) String author) {
    }

    /**
     * 电子书导入业务视图，不暴露内部调度任务标识。
     */
    public record ImportView(UUID id, String status, UUID sourceId, int sourceVersion,
                             String title, String author, Integer chapterCount, Long outputSize,
                             String sha256, String storageUri, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 电子书目录视图。
     */
    public record CatalogView(UUID importRequestId, List<Entry> entries) {
        /**
         * 目录条目。
         */
        public record Entry(int index, String title, String resourceRef) {
        }
    }
}
