package com.yuyutian.mytools.messaging.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 旧消息服务模板迁移项。 */
public record LegacyMessageTemplateItem(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sourceSystem,
        @NotBlank @Size(max = 255) String legacyTemplateId,
        @NotNull @PositiveOrZero Long ownerId,
        @NotNull ChannelType channelType,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2048) String description,
        @Size(max = 998) String subject,
        @Size(max = 10_485_760) String bodyText,
        @Size(max = 10_485_760) String bodyHtml,
        JsonNode variables,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt) {
}
