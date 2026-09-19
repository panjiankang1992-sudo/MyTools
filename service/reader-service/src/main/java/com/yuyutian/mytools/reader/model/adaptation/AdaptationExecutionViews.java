package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 仅返回执行所需的冻结输入，不返回 owner、locator、内部地址或凭据。 */
public final class AdaptationExecutionViews {
    private AdaptationExecutionViews() { }

    /** 冻结输入及受控 Provider 部署身份；不包含 API Key。 */
    public record Inputs(String intent, String promptVersion, String constraintVersion, String providerDeploymentId,
                         String providerCode, String modelId, long credentialGeneration, String disclosureVersion) {
        /** 防止诊断递归输出用户意图。 */
        @Override public String toString() { return "Inputs[content=REDACTED]"; }
    }

    /** 完整封存的角色正文；BUILDING 状态只返回空 context。 */
    public record Context(String manifestVersion, String manifestSha256, AdaptationRequestKind kind,
                          List<AdaptationContextFragment> fragments) {
        /** 防止诊断递归输出正文。 */
        @Override public String toString() { return "Context[content=REDACTED]"; }
    }

    /** 技术调用恢复提示，不包含待结算 token、请求或候选正文。 */
    public record PendingAttempt(UUID providerAttemptId, String callKind, String status, UUID executionId,
                                 long fencingToken, String requestSha256) { }

    /** 当前持久状态及预算，接管不能重置预算或隐瞒未决调用。 */
    public record State(UUID adaptationId, UUID executionId, long fencingToken, AdaptationStatus status,
                        String currentStage, Instant deadlineAt, boolean stopRequested, int callsRemaining,
                        int retriesRemaining, long providerMillisRemaining, List<PendingAttempt> pendingAttempts) { }

    /** 领取结果及下一动作，输入与正文只在已验证执行范围内返回。 */
    public record Claim(State state, String nextAction, Inputs inputs, Context context) {
        /** 防止诊断递归输出输入或上下文。 */
        @Override public String toString() { return "Claim[nextAction=" + nextAction + ", content=REDACTED]"; }
    }

    /** 将领域快照投影为不含 owner 或内部绑定标识的执行输入。 */
    public static Context context(AdaptationContextSnapshot snapshot) {
        return snapshot == null ? null : new Context(AdaptationContextSnapshot.MANIFEST_VERSION,
                snapshot.manifestSha256(), snapshot.kind(), snapshot.fragments());
    }
}
