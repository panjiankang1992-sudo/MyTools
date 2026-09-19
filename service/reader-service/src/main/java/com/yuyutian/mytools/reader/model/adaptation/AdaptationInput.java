package com.yuyutian.mytools.reader.model.adaptation;

import java.util.UUID;

/** API 白名单输入不含用户身份、正文、模型、地址或系统提示词。 */
public record AdaptationInput(String idempotencyKey, String intent, long expectedBindingRevision,
                              long expectedCatalogRevision, String expectedSourceSha256, String templateCode, Long templateVersion) {
    /** 兼容旧版调用方，历史任务不隐式升级提示词。 */
    public AdaptationInput(String idempotencyKey, String intent, long expectedBindingRevision,
                           long expectedCatalogRevision, String expectedSourceSha256) {
        this(idempotencyKey, intent, expectedBindingRevision, expectedCatalogRevision, expectedSourceSha256, null, null);
    }
    /** 将认证身份与服务端解析的目标合并为领域命令。 */
    public AdaptationCommand command(long ownerId, UUID shelfBookId, UUID chapterId,
                                     AdaptationRequestKind kind, UUID triggerId) {
        return new AdaptationCommand(ownerId, shelfBookId, chapterId, kind, triggerId, idempotencyKey, intent,
                expectedBindingRevision, expectedCatalogRevision, expectedSourceSha256, templateCode, templateVersion);
    }

    /** 避免请求日志自动输出意图或幂等键。 */
    @Override
    public String toString() {
        return "AdaptationInput[redacted]";
    }
}
