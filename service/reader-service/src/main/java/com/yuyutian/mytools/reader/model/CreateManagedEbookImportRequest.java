package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 创建来自 Media Library 的受管 TXT 电子书导入请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param mediaItemId Media Library 媒体条目标识
 * @param mediaAssetId 资产登记标识
 * @param title 冻结展示标题
 * @param mimeType 冻结 MIME 类型
 * @param sizeBytes 冻结字节大小
 * @param contentSha256 冻结内容摘要
 * @param rightsConfirmed 权利确认标记
 */
public record CreateManagedEbookImportRequest(@NotNull Long ownerId,
                                              @NotBlank @Size(max = 255) String idempotencyKey,
                                              @NotNull UUID mediaItemId,
                                              @NotNull UUID mediaAssetId,
                                              @NotBlank @Size(max = 300) String title,
                                              @NotBlank @Size(max = 255) String mimeType,
                                              @Positive long sizeBytes,
                                              @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String contentSha256,
                                              boolean rightsConfirmed) {
}
