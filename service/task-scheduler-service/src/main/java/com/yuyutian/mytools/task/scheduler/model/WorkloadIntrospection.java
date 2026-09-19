package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.UUID;

/** Reader 本地验签后发送的窄在线查询，不包含 JWT 或用户资源正文。 */
public final class WorkloadIntrospection {
    private WorkloadIntrospection() {
    }

    /** 当前授权标识及证书绑定，所有字段必须与权威行一致。 */
    public record Request(UUID jti, UUID taskInstanceId, UUID executionId, long fencingToken,
                          long assertionGeneration, String cnfThumbprint, String audience,
                          String resourceType, String taskParametersSha256) {
        /** 严格检查非内容字段，未知或畸形查询不能降级为通配符匹配。 */
        public Request {
            if (jti == null || taskInstanceId == null || executionId == null || fencingToken < 1 || assertionGeneration < 1
                    || cnfThumbprint == null || !cnfThumbprint.matches("[A-Za-z0-9_-]{43}")
                    || audience == null || !audience.matches("[a-z-]{1,64}") || resourceType == null
                    || !resourceType.matches("[A-Z_]{1,32}") || taskParametersSha256 == null || !taskParametersSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid workload introspection request");
            }
        }

        /** 避免把随机授权标识写到默认记录诊断中。 */
        @Override
        public String toString() {
            return "WorkloadIntrospection.Request[redacted]";
        }
    }

    /** active 不是可缓存权限，截止时间只限定本次检查的授权上界。 */
    public record Response(boolean active, Instant authorizedUntil) {
    }
}
