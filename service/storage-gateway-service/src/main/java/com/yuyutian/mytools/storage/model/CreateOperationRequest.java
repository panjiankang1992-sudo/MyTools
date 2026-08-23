package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 创建异步存储操作请求。
 *
 * @param idempotencyKey 幂等键
 * @param providerId Provider 标识
 * @param operationType 操作类型
 * @param sourcePath 起始路径
 * @param maximumObjects 最大对象数
 */
public record CreateOperationRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotNull UUID providerId,
        @NotBlank @Pattern(regexp = "^SCAN_ROOT$") String operationType,
        @Size(max = 2048) String sourcePath,
        @Min(1) @Max(1000000) int maximumObjects) {
}
