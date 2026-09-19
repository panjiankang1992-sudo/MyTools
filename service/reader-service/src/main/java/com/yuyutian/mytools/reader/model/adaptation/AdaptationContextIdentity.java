package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.util.UUID;

/** 从同一份已封存目录解析的上下文身份，不能由客户端声明书籍边界。 */
public record AdaptationContextIdentity(UUID adaptationId, long ownerId, UUID shelfBookId,
                                        UUID bindingId, long bindingRevision, long catalogRevision,
                                        UUID targetChapterId, int targetIndex, int catalogChapterCount,
                                        UUID previousChapterId, UUID nextChapterId) {

    /** 验证完整目录中的位置与真实相邻章节身份。 */
    public AdaptationContextIdentity {
        if (adaptationId == null || ownerId <= 0 || shelfBookId == null || bindingId == null
                || targetChapterId == null || bindingRevision <= 0 || catalogRevision <= 0
                || catalogChapterCount < 1 || catalogChapterCount > 50000
                || targetIndex < 0 || targetIndex >= catalogChapterCount) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
        // 只有目录首尾能缺少邻章，网络读取失败不能伪装成边界。
        if ((targetIndex == 0) != (previousChapterId == null)
                || (targetIndex == catalogChapterCount - 1) != (nextChapterId == null)
                || targetChapterId.equals(previousChapterId) || targetChapterId.equals(nextChapterId)
                || (previousChapterId != null && previousChapterId.equals(nextChapterId))) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
    }
}
