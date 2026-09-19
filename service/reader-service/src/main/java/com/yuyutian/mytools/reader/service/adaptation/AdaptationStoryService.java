package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationStoryRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 对受权执行公开固定创作用例，调用方不能改写正文、规则或判定结果。 */
@Service
public class AdaptationStoryService {
    private final AdaptationStoryRepository repository;

    /** 注入封存及采用的唯一事务入口。 */
    public AdaptationStoryService(AdaptationStoryRepository repository) { this.repository = repository; }

    /** 从既有计划调用一次性封存约束。 */
    public AdaptationStoryModels.Constraints seal(WorkloadAuthorization authorization, UUID planAttemptId) { return repository.seal(authorization, planAttemptId); }

    /** 只有完整候选落库后才进入校验阶段。 */
    public AdaptationStoryModels.Progress progress(WorkloadAuthorization authorization, String expectedStatus, String expectedStage, String nextStatus, String nextStage) {
        return repository.progress(authorization, expectedStatus, expectedStage, nextStatus, nextStage);
    }

    /** 由 Reader 重新计算并持久化校验，不接受调用方给定 PASS。 */
    public AdaptationStoryModels.Validation validate(WorkloadAuthorization authorization, UUID candidateId, UUID criticId) { return repository.validate(authorization, candidateId, criticId); }

    /** 原子采用已通过校验的候选，不再提交正文。 */
    public AdaptationStoryModels.Selection complete(WorkloadAuthorization authorization, UUID candidateId, UUID validationId) { return repository.complete(authorization, candidateId, validationId); }

    /** 使用固定错误码结束已无未决调用的版本。 */
    public AdaptationStoryModels.Progress fail(WorkloadAuthorization authorization, String code) { return repository.fail(authorization, code); }

    /** 当前执行读取同一事务核实的恢复证据，停止后仅返回状态。 */
    public AdaptationStoryModels.Workflow workflow(WorkloadAuthorization authorization) { return repository.workflow(authorization); }
}
