package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建书源发现请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param url 公网书源仓库地址
 */
public record CreateDiscoveryRequest(@NotNull Long ownerId,
                                     @NotBlank @Size(max = 255) String idempotencyKey,
                                     @NotBlank @Size(max = 4096) String url) {
}
