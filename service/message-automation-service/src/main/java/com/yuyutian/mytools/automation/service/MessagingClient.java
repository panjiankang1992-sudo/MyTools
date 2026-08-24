package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.model.InboundMessage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 标准入站消息只读客户端。
 */
public class MessagingClient {

    private final RestClient restClient;
    private final String token;

    /**
     * 创建标准消息客户端。
     *
     * @param restClient 消息服务 HTTP 客户端
     * @param token 内部访问令牌
     */
    public MessagingClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token;
    }

    /**
     * 使用消息标识读取完整标准消息。
     *
     * @param messageId 消息标识
     * @return 标准入站消息
     */
    public InboundMessage get(UUID messageId) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Messaging internal token is missing");
        }
        InboundMessage message = restClient.get().uri("/internal/v1/inbound-messages/{id}", messageId)
                .header("Authorization", "Bearer " + token).retrieve().body(InboundMessage.class);
        if (message == null || !messageId.equals(message.id())) {
            throw new IllegalStateException("Messaging Service returned an invalid message");
        }
        return message;
    }

    /**
     * 幂等创建标准消息附件下载任务。
     *
     * @param messageId 消息标识
     * @param partId 消息附件部分标识
     * @param ownerId 所有者标识
     * @return 附件任务快照
     */
    public AttachmentSnapshot createAttachment(UUID messageId, UUID partId, long ownerId) {
        AttachmentSnapshot result = restClient.post().uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/inbound-messages/{messageId}/parts/{partId}/download")
                        .queryParam("ownerId", ownerId).build(messageId, partId))
                .header("Authorization", "Bearer " + requiredToken())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(AttachmentSnapshot.class);
        return requiredAttachment(result);
    }

    /**
     * 查询标准消息附件下载任务。
     *
     * @param jobId 附件任务标识
     * @param ownerId 所有者标识
     * @return 附件任务快照
     */
    public AttachmentSnapshot attachment(UUID jobId, long ownerId) {
        AttachmentSnapshot result = restClient.get().uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/attachment-downloads/{id}")
                        .queryParam("ownerId", ownerId).build(jobId))
                .header("Authorization", "Bearer " + requiredToken()).retrieve().body(AttachmentSnapshot.class);
        return requiredAttachment(result);
    }

    /**
     * 取消标准消息附件下载任务。
     *
     * @param jobId 附件任务标识
     * @param ownerId 所有者标识
     * @return 附件任务快照
     */
    public AttachmentSnapshot cancelAttachment(UUID jobId, long ownerId) {
        AttachmentSnapshot result = restClient.post().uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/attachment-downloads/{id}/cancel")
                        .queryParam("ownerId", ownerId).build(jobId))
                .header("Authorization", "Bearer " + requiredToken())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(AttachmentSnapshot.class);
        return requiredAttachment(result);
    }

    private AttachmentSnapshot requiredAttachment(AttachmentSnapshot value) {
        if (value == null || value.id() == null || value.status() == null || value.status().isBlank()) {
            throw new IllegalStateException("Messaging Service returned an invalid attachment task");
        }
        return value;
    }

    private String requiredToken() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Messaging internal token is missing");
        }
        return token;
    }

    /** 附件任务最小状态快照。 */
    public record AttachmentSnapshot(UUID id, String status) {
    }
}
