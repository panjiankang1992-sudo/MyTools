package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository.SourceExecutionSnapshot;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.util.UUID;

/** 版本化运行时调用身份，由准备任务持久分配，不能使用客户端提供的命名空间。 */
public record ReaderRuntimeInvocation(long ownerId, UUID sourceId, int sourceVersion, UUID invocationId) {

    /** 拒绝缺失的租户、书源版本或调用标识。 */
    public ReaderRuntimeInvocation {
        if (ownerId <= 0 || sourceId == null || sourceVersion <= 0 || invocationId == null) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
    }

    /** 返回只包含已验证身份的隔离命名空间，不携带地址、密钥或正文。 */
    public String namespace() {
        return ownerId + ":reader:v2:" + sourceId + ":" + sourceVersion + ":" + invocationId;
    }

    /** 在安装规则前检查 exact 版本匹配，避免调用身份与规则快照错配。 */
    public void requireSnapshot(SourceExecutionSnapshot snapshot) {
        if (snapshot == null || !sourceId.equals(snapshot.id()) || sourceVersion != snapshot.version()
                || snapshot.snapshot() == null || snapshot.sourceUrl() == null || snapshot.sourceUrl().isBlank()
                || !snapshot.sourceUrl().equals(snapshot.snapshot().get("bookSourceUrl"))) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
    }
}
