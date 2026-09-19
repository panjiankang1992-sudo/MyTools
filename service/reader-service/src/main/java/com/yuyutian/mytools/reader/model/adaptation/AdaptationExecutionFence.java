package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.time.Instant;
import java.util.UUID;

/** 内部授权层验证 Scheduler 断言后产生的执行范围，不能直接绑定 HTTP 请求体。 */
public record AdaptationExecutionFence(UUID adaptationId, UUID taskInstanceId, UUID executionId,
                                       long fencingToken, long chapterDeleteEpoch, Instant authorizedUntil) {
    /** 校验执行范围形状；签名、租约、证书和撤销检查仍由内部授权层负责。 */
    public AdaptationExecutionFence {
        if (adaptationId == null || taskInstanceId == null || executionId == null || fencingToken <= 0
                || chapterDeleteEpoch < 0 || authorizedUntil == null) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
    }
}
