package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.util.UUID;

/** 不可变版本谱系，数据库外键另行保证所有节点属于同一用户与章节。 */
public record AdaptationLineage(UUID childAdaptationId, UUID rootAdaptationId,
                                UUID parentAdaptationId, UUID triggerAdaptationId) {

    /** 校验谱系基本完整性，禁止自引用派生边。 */
    public AdaptationLineage {
        if (childAdaptationId == null || rootAdaptationId == null
                || childAdaptationId.equals(parentAdaptationId) || childAdaptationId.equals(triggerAdaptationId)
                || (parentAdaptationId != null && !parentAdaptationId.equals(triggerAdaptationId))) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        if ((triggerAdaptationId == null) != childAdaptationId.equals(rootAdaptationId)) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
    }

    /** 创建首次改编的根版本。 */
    public static AdaptationLineage initial(UUID childId) {
        return new AdaptationLineage(childId, childId, null, null);
    }

    /** 从已由服务端验证的触发版本生成优化子版本或重新改编分支。 */
    public static AdaptationLineage derive(UUID childId, AdaptationRequestKind kind, AdaptationLineage trigger) {
        if (trigger == null || kind == null || kind == AdaptationRequestKind.INITIAL) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        // 重新改编继承根版本，但不把触发结果当作底稿父版本。
        UUID parentId = kind == AdaptationRequestKind.OPTIMIZE ? trigger.childAdaptationId() : null;
        return new AdaptationLineage(childId, trigger.rootAdaptationId(), parentId, trigger.childAdaptationId());
    }
}
