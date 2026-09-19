package com.yuyutian.mytools.reader.model.adaptation;

import java.util.UUID;

/** 仅将非正文授权身份发送给 Scheduler，字段与其受限内省契约一致。 */
public record WorkloadIntrospection(UUID jti, UUID taskInstanceId, UUID executionId, long fencingToken,
                                    long assertionGeneration, String cnfThumbprint, String audience,
                                    String resourceType, String taskParametersSha256) {
    /** 诊断不记录原始 jti 或证书信息。 */
    @Override
    public String toString() { return "WorkloadIntrospection[identity=REDACTED]"; }
}
