package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建受控上传请求。
 *
 * @param rootName 受管根名称
 * @param relativePath 根内相对路径
 * @param expectedSize 预期字节数
 * @param expectedSha256 可选 SHA-256
 * @param idempotencyKey 幂等键
 */
public record CreateUploadRequest(@NotBlank @Size(max = 128) String rootName,
                                  @NotBlank @Size(max = 2048) String relativePath,
                                  @Positive long expectedSize,
                                  @Size(min = 64, max = 64) String expectedSha256,
                                  @NotBlank @Size(max = 255) String idempotencyKey) {
}
