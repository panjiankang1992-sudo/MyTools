package com.yuyutian.mytools.messaging.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 旧消息服务导出的标准化历史发件记录。
 */
public record LegacyOutboundMessageItem(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sourceSystem,
        @NotBlank @Size(max = 255) String legacyMessageId,
        @NotNull @PositiveOrZero Long ownerId,
        @NotNull ChannelType channelType,
        @NotBlank @Pattern(regexp = "^(SENT|FAILED)$") String status,
        @Size(max = 1024) String sender,
        @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 1024) String> recipients,
        @Size(max = 998) String subject,
        @Size(max = 10_485_760) String bodyText,
        @Size(max = 10_485_760) String bodyHtml,
        @Valid @Size(max = 100) List<LegacyAttachmentArchive> attachments,
        @Size(max = 255) String templateRef,
        @Size(max = 512) String providerMessageId,
        @Size(max = 255) String errorCode,
        Instant sentAt,
        @NotNull Instant createdAt) {

    /** 返回不可变收件人集合。 */
    @Override public List<String> recipients() { return List.copyOf(recipients); }

    /** 返回不可变附件集合。 */
    @Override public List<LegacyAttachmentArchive> attachments() {
        return attachments == null ? List.of() : List.copyOf(attachments);
    }
}
