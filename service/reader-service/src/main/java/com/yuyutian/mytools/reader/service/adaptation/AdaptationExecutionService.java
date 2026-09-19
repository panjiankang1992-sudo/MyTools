package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionViews;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationExecutionRepository;
import org.springframework.stereotype.Service;

/** 连接受权执行领取与既有受控上下文准备；不提供任意正文写入。 */
@Service
public class AdaptationExecutionService {
    private final AdaptationExecutionRepository repository;
    private final AdaptationContextService contexts;

    /** 注入短事务执行仓储和锁外正文读取器。 */
    public AdaptationExecutionService(AdaptationExecutionRepository repository, AdaptationContextService contexts) {
        this.repository = repository;
        this.contexts = contexts;
    }

    /** 首次或更高 fence 领取；不覆盖之前的业务版本。 */
    public AdaptationExecutionViews.Claim claim(WorkloadAuthorization authorization) { return repository.claim(authorization); }

    /** 只通过当前 fence 准备完整正文；返回前再次检查取消、期限及是否发生接管。 */
    public AdaptationExecutionViews.Context prepare(WorkloadAuthorization authorization) {
        var result = contexts.prepare(repository.current(authorization));
        repository.current(authorization);
        return AdaptationExecutionViews.context(result);
    }

    /** 获取轻量执行状态和原始剩余预算。 */
    public AdaptationExecutionViews.State status(WorkloadAuthorization authorization) { return repository.status(authorization); }
}
