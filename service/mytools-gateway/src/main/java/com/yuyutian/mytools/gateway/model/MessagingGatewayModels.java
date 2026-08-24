package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Messaging Gateway 安全响应模型。
 */
public final class MessagingGatewayModels {
    private MessagingGatewayModels() { }
    public record MessagePart(UUID id,int sequence,String type,String text,String attachmentType,
                              String fileName,String mimeType,Long declaredSize) { }
    public record MessageView(UUID id,long ownerId,String channelType,String sender,String subject,String body,
                              Instant receivedAt,Instant createdAt,List<MessagePart> parts) { }
    public record MessagePage(List<MessageView> items,UUID nextAfterId) { }
    public record AttachmentDownloadView(UUID id,UUID messageId,UUID partId,String status,
                                         String lastErrorCode,Instant createdAt,Instant updatedAt) { }
    public record CreateEmail(@NotBlank @Size(max=255)String idempotencyKey,
                              @NotBlank @Email @Size(max=1024)String recipient,
                              @Size(max=998)String subject,
                              @NotBlank @Size(max=10_485_760)String body) { }
    public record DeliveryView(UUID id,String channelType,String recipient,String status,
                               String lastErrorCode,Instant createdAt,Instant updatedAt) { }
}
