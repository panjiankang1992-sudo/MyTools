package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/** 模型边界中的受限数据，不接受可变端点、任意消息角色或工具配置。 */
public final class NovelProviderModels {
    private NovelProviderModels() { }

    /** 阶段时间上界与 Reader 持久预占协议一致。 */
    public enum Phase {
        PLAN(90, 0.2), GENERATE(180, 0.7), CRITIC(105, 0.0), REPAIR(180, 0.4);
        private final int seconds;
        private final double temperature;
        Phase(int seconds, double temperature) { this.seconds = seconds; this.temperature = temperature; }
        /** 返回单次持久预占的时间上界。 */
        public int seconds() { return seconds; }
        /** 返回版本化默认采样温度。 */
        public double temperature() { return temperature; }
    }

    /** 仅由宿主已验证发布配置构造，字节上限不是 tokenizer 或模型能力证明。 */
    public record Deployment(String id, String model, long credentialGeneration, int maximumRequestBytes,
                             int maximumOutputTokens, boolean acceptSse) {
        /** 拒绝隐式模型、非法发布身份和无界请求。 */
        public Deployment {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")
                    || credentialGeneration < 1 || maximumRequestBytes < 1024 || maximumRequestBytes > 2_097_152
                    || maximumOutputTokens < 1 || maximumOutputTokens > 120000) {
                throw new NovelProviderException(ErrorCode.CONTEXT);
            }
        }

        /** 返回 Reader 能权威冻结的发布身份，不要求 Reader 声明宿主传输参数。 */
        public Scope scope() { return new Scope(id, model, credentialGeneration); }
    }

    /** 从已授权 Reader 输入映射的发布身份，不包含认证或传输参数。 */
    public record Scope(String deploymentId, String modelId, long credentialGeneration) { }

    /** 发送权由已认证 Reader 发送许可映射，不能从子进程的自报数据直接构造。 */
    public record Permit(UUID providerAttemptId, String requestSha256, boolean maySend,
                         Instant sendBy, Instant callDeadlineAt) { }

    /** 成功正文只交给阶段归一器；失败结果只包含元数据和摘要。 */
    public record Result(String status, @JsonIgnore String content, String finishReason, String providerRequestId,
                         Integer inputTokens, Integer outputTokens, Integer httpStatus,
                         String errorCode, String diagnosticSha256) {
        /** 外部错误载荷不得进入日志或默认对象文本。 */
        @Override public String toString() {
            return "NovelProviderResult[status=" + status + ", errorCode=" + errorCode + "]";
        }
    }
}
