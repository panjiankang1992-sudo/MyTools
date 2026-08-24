package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 旧消息服务已知收件人迁移项。 */
public record LegacyKnownRecipientItem(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sourceSystem,
        @NotBlank @Size(max = 255) String legacyRecipientId,
        @NotNull @PositiveOrZero Long ownerId,
        @NotNull ChannelType channelType,
        @NotBlank @Size(max = 1024) String address,
        @Size(max = 255) String name,
        @NotNull Instant createdAt) {
}
