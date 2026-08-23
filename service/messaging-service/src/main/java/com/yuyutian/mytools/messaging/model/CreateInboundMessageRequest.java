package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 标准化入站消息写入请求。
 */
public record CreateInboundMessageRequest(@NotNull Long ownerId,
                                          @NotNull ChannelType channelType,
                                          @NotBlank @Size(max = 512) String externalMessageId,
                                          @NotBlank @Size(max = 512) String conversationKey,
                                          @NotBlank @Size(max = 1024) String sender,
                                          @Size(max = 998) String subject,
                                          @NotBlank @Size(max = 10_485_760) String body,
                                          @NotNull Instant receivedAt) {
}
