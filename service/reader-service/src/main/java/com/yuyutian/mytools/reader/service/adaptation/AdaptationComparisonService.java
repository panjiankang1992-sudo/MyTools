package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationComparison;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationContextRepository;
import com.yuyutian.mytools.reader.repository.adaptation.ChapterAdaptationRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 历史比较不依赖书源或正文缓存，来源不可用时仍比较当次生成实际使用的原文。 */
@Service
public class AdaptationComparisonService {
    private final ChapterAdaptationRepository versions;
    private final AdaptationContextRepository contexts;
    private final AdaptationComparisonEngine engine;

    /** 复用版本所有权、采用关系和快照完整性校验。 */
    public AdaptationComparisonService(ChapterAdaptationRepository versions, AdaptationContextRepository contexts,
                                       AdaptationComparisonEngine engine) {
        this.versions = versions;
        this.contexts = contexts;
        this.engine = engine;
    }

    /** 只比较通过校验的采用正文；失败或处理中版本没有伪造比较结果。 */
    public AdaptationComparison compare(long ownerId, UUID adaptationId) {
        var detail = versions.detail(ownerId, adaptationId);
        if (detail.result() == null) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND);
        }
        var original = contexts.original(ownerId, adaptationId);
        return engine.compare(adaptationId, detail.result().attemptId(), original.text(), detail.result().content());
    }
}
