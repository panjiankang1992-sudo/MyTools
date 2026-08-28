package com.yuyutian.mytools.automation.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 下载编排任务批量登记消息来源链接的请求。
 */
public record ClaimMessageLinksRequest(@PositiveOrZero long ownerId, @NotNull UUID messageId,
                                       @NotNull Instant processedAt,
                                       @NotEmpty @Size(max = 100)
                                       List<@NotBlank @Size(max = 4096) String> urls) {
}
