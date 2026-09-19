package com.yuyutian.mytools.reader.model.adaptation;

import java.util.List;
import java.util.UUID;

/** 约束、校验与采用的独立内部投影，正文与内部事实清单不进入诊断。 */
public final class AdaptationStoryModels {
    private AdaptationStoryModels() { }

    /** 已封存的确定性规则、模型补充和不可被模型删减的合并约束。 */
    public record Constraints(String deterministicJson, String supplementJson, String mergedJson,
                               String deterministicSha256, String supplementSha256, String mergedSha256) {
        /** 隐藏全部内部规则和原文证据。 */
        @Override
        public String toString() { return "Constraints[content=REDACTED]"; }
    }

    /** 规则发现只返回固定类别和约束引用，不把原文带入错误信息。 */
    public record Finding(String code, String reference) { }

    /** 校验结果不可变，结构化明细仅用于内部修复输入。 */
    public record Evaluation(String outcome, String contentPolicyOutcome, String deterministicJson, String criticJson,
                              String reportJson, String reportSha256, List<Finding> findings) {
        /** 固定结果集合，避免调用方改写已经计算的摘要。 */
        public Evaluation { findings = List.copyOf(findings); }
        /** 报告不进入诊断日志。 */
        @Override
        public String toString() { return "Evaluation[outcome=" + outcome + ", content=REDACTED]"; }
    }

    /** 每次状态推进返回持久状态和版本，不包含生成内容。 */
    public record Progress(UUID adaptationId, AdaptationStatus status, String currentStage, long version) { }

    /** 已落库校验的稳定收据，使用原 ID 幂等重放。 */
    public record Validation(UUID validationId, UUID candidateAttemptId, UUID criticAttemptId, int round,
                             String outcome, String contentPolicyOutcome, String reportSha256, Progress progress) { }

    /** 正式采用只引用通过校验的候选，不重新接受正文。 */
    public record Selection(UUID candidateAttemptId, UUID validationId, Progress progress) { }

    /** 恢复用调用摘要；只有当前仍有效的执行能取得非留档成功结果。 */
    public record Attempt(UUID attemptId, UUID providerAttemptId, int attemptNo, String callKind, String status,
                          boolean archivedOnly, String errorCode, String outputText, String structuredJson) {
        /** 不将恢复正文或结构化结果写入诊断。 */
        @Override public String toString() { return "WorkflowAttempt[attemptNo=" + attemptNo + ", content=REDACTED]"; }
    }

    /** 由 Reader 重算验证的修复证据，不接受调用方提供的 PASS 或报告。 */
    public record Review(UUID validationId, UUID candidateAttemptId, UUID criticAttemptId, int round,
                         String outcome, String contentPolicyOutcome, String deterministicJson, String criticJson,
                         String reportJson, String reportSha256) {
        /** 隐藏所有校验正文。 */
        @Override public String toString() { return "WorkflowReview[round=" + round + ", content=REDACTED]"; }
    }

    /** 单事务读取的当前恢复视图；停止后不再暴露调用载荷、约束或报告。 */
    public record Workflow(AdaptationExecutionViews.State state, String errorCode, List<Attempt> attempts,
                           Constraints constraints, Review review) {
        /** 返回固定调用列表，避免在事务外改变读取证据。 */
        public Workflow { attempts = List.copyOf(attempts); }
        /** 不递归输出恢复内容。 */
        @Override public String toString() { return "Workflow[state=" + state.status() + ", content=REDACTED]"; }
    }
}
