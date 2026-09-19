package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.util.Arrays;
import java.util.UUID;

/** 服务端完成身份解析后的命令，不能直接作为公共 API 请求体。 */
public record AdaptationCommand(long ownerId, UUID shelfBookId, UUID chapterId,
                                AdaptationRequestKind kind, UUID triggerAdaptationId,
                                String idempotencyKey, String intent,
                                long expectedBindingRevision, long expectedCatalogRevision,
                                String expectedSourceSha256, String templateCode, Long templateVersion) {

    public static final String CANONICALIZATION_VERSION = "adaptation-command-framed-v1";

    /** 兼容旧客户端与旧收据的无模板命令。 */
    public AdaptationCommand(long ownerId, UUID shelfBookId, UUID chapterId, AdaptationRequestKind kind,
                             UUID triggerAdaptationId, String idempotencyKey, String intent,
                             long expectedBindingRevision, long expectedCatalogRevision, String expectedSourceSha256) {
        this(ownerId, shelfBookId, chapterId, kind, triggerAdaptationId, idempotencyKey, intent,
                expectedBindingRevision, expectedCatalogRevision, expectedSourceSha256, null, null);
    }

    /** 校验用户范围、版本意图和操作语义，正文由后端另行读取。 */
    public AdaptationCommand {
        if (ownerId <= 0 || shelfBookId == null || chapterId == null || kind == null) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        if (expectedBindingRevision <= 0 || expectedCatalogRevision <= 0) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        if ((kind == AdaptationRequestKind.INITIAL) != (triggerAdaptationId == null)) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        AdaptationText.requireIdempotencyKey(idempotencyKey);
        AdaptationText.normalizeIntent(intent);
        if ((templateCode == null) != (templateVersion == null)
                || (templateCode != null && (!templateCode.matches("[a-z][a-z0-9-]{0,63}")
                || templateVersion < 1 || templateVersion > 1000000))) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
        }
        if (expectedSourceSha256 != null) {
            AdaptationText.requireSha256(expectedSourceSha256, ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
    }

    /** 返回用于幂等比较和生成的已验证意图，原始输入仍保留于命令。 */
    public String normalizedIntent() {
        return AdaptationText.normalizeIntent(intent);
    }

    /** 冻结所有语义字段；幂等键自身不参与请求内容指纹。 */
    public String fingerprint() {
        var fields = new java.util.ArrayList<>(Arrays.asList(
                Long.toString(ownerId), shelfBookId.toString(), chapterId.toString(), kind.name(),
                triggerAdaptationId == null ? null : triggerAdaptationId.toString(), normalizedIntent(),
                Long.toString(expectedBindingRevision), Long.toString(expectedCatalogRevision), expectedSourceSha256));
        // 旧命令保持原字节序列，新命令同时冻结模板修订。
        if (templateCode != null) { fields.add(templateCode); fields.add(templateVersion.toString()); }
        return AdaptationText.fingerprint(canonicalizationVersion(), fields);
    }

    /** 只对包含模板的新请求启用新版规范化。 */
    public String canonicalizationVersion() {
        return templateCode == null ? CANONICALIZATION_VERSION : "adaptation-command-framed-v2";
    }

    /** 只返回非内容诊断，避免记录类型默认输出泄漏用户意图和幂等键。 */
    @Override
    public String toString() {
        return "AdaptationCommand[kind=" + kind + ", content=redacted]";
    }
}
