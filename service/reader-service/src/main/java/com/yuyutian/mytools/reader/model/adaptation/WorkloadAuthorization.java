package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;
import java.util.UUID;

/** 由本地验签和本次在线撤销检查产生的授权；HTTP 控制器不得从请求体绑定此类型。 */
public record WorkloadAuthorization(WorkloadResource resource, UUID resourceId, UUID taskInstanceId, UUID executionId,
                                    long fencingToken, Instant authorizedUntil, String certificateThumbprint) {
    /** 兼容不签发调用许可的旧内部调用者；缺少证书绑定时不能预留或发送模型调用。 */
    public WorkloadAuthorization(WorkloadResource resource, UUID resourceId, UUID taskInstanceId, UUID executionId,
                                 long fencingToken, Instant authorizedUntil) {
        this(resource, resourceId, taskInstanceId, executionId, fencingToken, authorizedUntil, null);
    }
}
