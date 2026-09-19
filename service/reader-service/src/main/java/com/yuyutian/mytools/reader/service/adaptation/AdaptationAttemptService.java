package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationAttemptRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 内部模型调用用例，不接受可由 App 声明的 owner 或发布配置。 */
@Service
public class AdaptationAttemptService {
    private final AdaptationAttemptRepository ledger;

    /** 注入唯一持久账本，网络调用不进入该服务事务。 */
    public AdaptationAttemptService(AdaptationAttemptRepository ledger) { this.ledger = ledger; }

    /** 持久预占一个固定阶段的调用及时间预算。 */
    public AdaptationAttemptModels.Reservation reserve(WorkloadAuthorization authorization, AdaptationAttemptModels.Reserve input) {
        return ledger.reserve(authorization, input);
    }

    /** 发放一次性发送许可，重放仅恢复原结算回执。 */
    public AdaptationAttemptModels.SendPermit sendStarted(WorkloadAuthorization authorization, UUID providerAttemptId) {
        return ledger.sendStarted(authorization, providerAttemptId);
    }

    /** 在接收正文前验证原生 TLS 所绑定的窄结算能力。 */
    public void verifySettlement(UUID adaptationId, UUID executionId, UUID providerAttemptId, String certificate, String token) {
        ledger.verifySettlement(adaptationId, executionId, providerAttemptId, certificate, token);
    }

    /** 以规范终态幂等留存结果，是否可继续推进由当前授权及业务状态决定。 */
    public AdaptationAttemptModels.Settlement settle(UUID adaptationId, UUID executionId, UUID providerAttemptId, String certificate,
                                                     String token, WorkloadAuthorization current, AdaptationAttemptModels.Terminal payload) {
        return ledger.settle(adaptationId, executionId, providerAttemptId, certificate, token, current, payload);
    }

    /** 收敛过期未决调用，不进行任何 Provider 重发。 */
    public int recover(WorkloadAuthorization authorization) { return ledger.recover(authorization); }
}
