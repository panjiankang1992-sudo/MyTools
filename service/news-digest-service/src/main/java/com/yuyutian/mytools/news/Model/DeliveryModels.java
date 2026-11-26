package com.yuyutian.mytools.news.Model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 新闻投递只引用 Messaging 中已经验证的目标。 */
public final class DeliveryModels {
    private DeliveryModels() { }
    /** QQ 保留能力标识，但不允许建立定时目标。 */
    public enum Channel { EMAIL, FEISHU, QQ }
    /** 完整版保留全部详情；精简版保留标题和每类前三条可用详情。 */
    public enum RenderMode { FULL, BRIEF }
    /** 远端脱敏目标视图。 */
    public record Destination(UUID id, Channel channel, String label, boolean enabled, Instant createdAt) { }
    /** 用户选择只接受不透明标识，不接受地址或渠道凭据。 */
    public record Selection(@NotNull UUID destinationId, @NotNull RenderMode mode, boolean enabled) { }
    /** 整体替换时检查预期版本，最多五个目标。 */
    public record TargetChange(@Min(0) long expectedRevision, @NotNull @Size(max=5) List<@Valid Selection> targets) { }
    /** 已核实的目标快照。 */
    public record Target(UUID destinationId, Channel channel, RenderMode mode, boolean enabled) { }
    /** 接收设置与内容订阅独立版本，变更从下一次内容运行生效。 */
    public record Targets(long revision, List<Target> targets) { }
    /** 固定文本与 HTML 使用与 Messaging v2 相同的摘要协议。 */
    public record Content(String subject, String text, String html) { }
    /** 每个片段保留独立稳定身份。 */
    public record RenderedPart(int index, int count, Content content, String payloadHash) { }
    /** 持久批次不存储提供方地址。 */
    public record Batch(UUID id, UUID runId, UUID subscriptionId, long ownerId, long targetRevision,
            UUID destinationId, Channel channel, RenderMode mode, UUID editionId, String state,
            boolean cancelRequested, Instant expiresAt, Instant createdAt, String errorCode) { }
    /** 内部出站快照，正文只在服务之间传递。 */
    public record Dispatch(UUID id, UUID batchId, int index, int count, Content content, String payloadHash,
            String status, UUID remoteId, int remoteGeneration, Integer retryGeneration, boolean retryAck, int attempts) { }
    /** 提供方结果的脱敏片段状态。 */
    public record PartView(UUID id, int index, int count, String status, int generation, String errorCode, boolean retryPending) { }
    /** 一个日报在一个目标上的完整投递状态。 */
    public record BatchView(UUID id, UUID runId, UUID editionId, UUID destinationId, Channel channel, RenderMode mode,
            String status, boolean cancelRequested, Instant expiresAt, Instant createdAt, String errorCode, List<PartView> parts) { }
    /** 短租约仅用于内部对账，不能暴露给客户端。 */
    public record Claim(UUID id, UUID token) { }
    /** 远端响应必须与冻结内容和目标一致。 */
    public record Receipt(UUID id, UUID destinationId, Channel channel, String payloadHash, String status,
            int generation, String errorCode, Instant expiresAt, Instant updatedAt) { }
    /** 已知失败和未知结果分别处理，重放同一代重试不能再次发送。 */
    public record Retry(@Min(1) @Max(99) int expectedGeneration, boolean acknowledgeDuplicateRisk) { }
}
