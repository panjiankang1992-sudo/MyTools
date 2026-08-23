package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建书源健康检查请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param keyword 用于执行搜索规则的探测词
 */
public record CreateHealthCheckRequest(@NotNull Long ownerId,
                                       @NotBlank @Size(max = 255) String idempotencyKey,
                                       @NotBlank @Size(max = 200) String keyword) {
}
