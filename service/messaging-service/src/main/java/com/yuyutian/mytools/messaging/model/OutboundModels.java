package com.yuyutian.mytools.messaging.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** 新版投递协议只向调用方暴露不透明目的地标识。 */
public final class OutboundModels {
    private OutboundModels() { }
    /** QQ 主动推送由平台停止提供，仅保留明确的能力状态。 */
    public enum Channel { EMAIL, FEISHU, QQ }
    /** 地址和签名凭据只能通过独立管理入口登记。 */
    public record Binding(@Min(1) long ownerId, @NotBlank @Size(max=128) String bindingKey,
            @NotNull Channel channel, @NotBlank @Size(max=120) String label,
            @NotBlank @Size(max=2048) String address, @Size(max=256) String signingSecret,
            @NotBlank @Size(max=255) String verificationReference, boolean ownershipVerified) {
        /** 避免调试输出包含邮箱和 Webhook。 */
        @Override public String toString() { return "Binding[redacted]"; }
    }
    /** 不返回地址或其他所有者信息。 */
    public record Destination(UUID id, Channel channel, String label, boolean enabled, Instant createdAt) { }
    /** 内部凭据仅用于单次提供方调用。 */
    public record Secret(String address, String signingSecret) {
        /** 屏蔽地址与签名密钥。 */
        @Override public String toString() { return "Secret[redacted]"; }
    }
    /** 邮件采用纯文本与 HTML 双版本，飞书只接受纯文本。 */
    public record Content(@NotBlank @Size(max=200) String subject,
            @NotBlank @Size(max=200000) String text, @Size(max=400000) String html) { }
    /** 幂等请求包含冻结内容摘要，客户端不能直接提供发送地址。 */
    public record Create(@Min(1) long ownerId, @NotBlank @Size(max=128) String idempotencyKey,
            @NotNull UUID destinationId, @NotNull @Valid Content content,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash, @NotNull Instant expiresAt, Instant contentExpiresAt) {
        /** 旧调用方未声明正文期限时继续使用原有保留策略。 */
        public Create(long ownerId,String idempotencyKey,UUID destinationId,Content content,String payloadHash,Instant expiresAt) {
            this(ownerId,idempotencyKey,destinationId,content,payloadHash,expiresAt,null);
        }
    }
    /** 正文已清除后仍可凭原始严格摘要取消未知创建，不提供任何可发送内容。 */
    public record CancelIdentity(@Min(1) long ownerId,@NotBlank @Size(max=128) String idempotencyKey,
            @NotNull UUID destinationId,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String contentIdentityHash,@NotNull Instant expiresAt,Instant contentExpiresAt) { }
    /** 状态不包含新闻正文、凭据或提供方内部标识。 */
    public record View(UUID id, UUID destinationId, Channel channel, String payloadHash, String status,
            int generation, String errorCode, Instant expiresAt, Instant updatedAt,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Instant contentExpiresAt) {
        /** 保留未声明正文期限的调用方式与响应字段。 */
        public View(UUID id,UUID destinationId,Channel channel,String payloadHash,String status,int generation,String errorCode,Instant expiresAt,Instant updatedAt) {
            this(id,destinationId,channel,payloadHash,status,generation,errorCode,expiresAt,updatedAt,null);
        }
    }
    /** 未知结果必须先明确处理重复风险，不能被普通重试自动覆盖。 */
    public record Retry(@Min(1) @Max(99) int expectedGeneration, boolean acknowledgeDuplicateRisk) { }
    /** 人工核对只允许标记已接受或确认未送出，保留审计依据。 */
    public record Resolution(@NotBlank @Pattern(regexp="PROVIDER_ACCEPTED|NOT_SENT") String outcome,
            @NotBlank @Size(max=255) String reference) { }
    /** 持久内部记录。 */
    public record Delivery(UUID id, long ownerId, String idempotencyKey, UUID destinationId, Channel channel,
            Content content, String payloadHash, String status, int generation, UUID taskId, UUID attemptId,
            long lastFence, Instant expiresAt, Instant updatedAt, String errorCode, String contentIdentityHash,Instant contentExpiresAt) {
        /** 兼容仅包含严格内容摘要的既有持久记录构造方式。 */
        public Delivery(UUID id,long ownerId,String idempotencyKey,UUID destinationId,Channel channel,Content content,
                String payloadHash,String status,int generation,UUID taskId,UUID attemptId,long lastFence,Instant expiresAt,
                Instant updatedAt,String errorCode,String contentIdentityHash) {
            this(id,ownerId,idempotencyKey,destinationId,channel,content,payloadHash,status,generation,taskId,attemptId,lastFence,expiresAt,updatedAt,errorCode,contentIdentityHash,null);
        }
        /** 保留既有内部构造方式，压缩记录额外保存严格内容摘要。 */
        public Delivery(UUID id, long ownerId, String idempotencyKey, UUID destinationId, Channel channel,
                Content content, String payloadHash, String status, int generation, UUID taskId, UUID attemptId,
                long lastFence, Instant expiresAt, Instant updatedAt, String errorCode) {
            this(id,ownerId,idempotencyKey,destinationId,channel,content,payloadHash,status,generation,taskId,attemptId,lastFence,expiresAt,updatedAt,errorCode,null,null);
        }
        /** 禁止在日志中输出完整正文。 */
        @Override public String toString() { return "Delivery[" + id + "," + status + "]"; }
    }
    /** 当前执行租约仅用于在线校验。 */
    public record Proof(UUID executionId, UUID leaseToken) {
        /** 屏蔽执行租约。 */
        @Override public String toString() { return "Proof[redacted]"; }
    }
    /** Scheduler 授予的最小权限。 */
    public record Grant(UUID taskId, String businessId, String idempotencyKey, long fencingToken, Instant leaseUntil) { }
}
