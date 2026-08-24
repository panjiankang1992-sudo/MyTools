package com.yuyutian.mytools.gateway.model;

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
}
