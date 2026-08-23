package com.yuyutian.mytools.asset.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 显式失效一个资产位置的请求。
 *
 * @param expectedAssetVersion 资产预期版本
 * @param idempotencyKey 幂等键
 * @param reason 稳定失效原因
 */
public record InvalidateLocationRequest(@Positive long expectedAssetVersion,
                                        @NotBlank @Size(max = 255) String idempotencyKey,
                                        @NotBlank @Size(max = 255) String reason) {
}
