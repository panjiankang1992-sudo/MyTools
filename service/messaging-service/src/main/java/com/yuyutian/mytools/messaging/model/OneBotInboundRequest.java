package com.yuyutian.mytools.messaging.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * OneBot 适配器入站请求。
 */
public record OneBotInboundRequest(@NotNull @Positive Long ownerId,
                                   @NotBlank @Size(max = 255) String accountId,
                                   @NotNull JsonNode event) {
}
