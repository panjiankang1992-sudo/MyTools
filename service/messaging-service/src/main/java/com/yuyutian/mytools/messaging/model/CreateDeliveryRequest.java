package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 创建标准投递请求。
 */
public record CreateDeliveryRequest(@NotNull Long ownerId,
                                    @NotBlank @Size(max = 255) String idempotencyKey,
                                    @NotNull ChannelType channelType,
                                    UUID accountId,
                                    @NotBlank @Size(max = 1024) String recipient,
                                    @Size(max = 998) String subject,
                                    @NotBlank @Size(max = 10_485_760) String body) {

    /**
     * 判断邮件收件地址是否符合基础格式。
     */
    public boolean validRecipient() {
        return channelType != ChannelType.EMAIL || recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }
}
