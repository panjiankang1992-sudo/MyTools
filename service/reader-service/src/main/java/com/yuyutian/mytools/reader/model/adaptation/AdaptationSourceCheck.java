package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;
import java.util.UUID;

/** 短期来源核验状态；CURRENT 不是未来书源不变的保证，也不是模型发送许可。 */
public record AdaptationSourceCheck(UUID adaptationId, String status, Instant sourceCheckedAt, Instant validUntil,
                                     int pollAfterMs, String reasonCode, long bindingRevision,
                                     long catalogRevision, String sourceSha256) {
    /** 领取身份只在 Reader 内部流转，没有正文或外部凭据。 */
    public record Claim(long ownerId, UUID adaptationId, UUID checkId, Instant deadline) { }
}
