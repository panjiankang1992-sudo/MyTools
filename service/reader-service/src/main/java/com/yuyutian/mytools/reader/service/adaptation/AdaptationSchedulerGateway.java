package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.SchedulerView;

import java.util.UUID;

/** 受限调度控制面，不传输 owner、正文、意图、模型或执行授权。 */
public interface AdaptationSchedulerGateway {
    /** 幂等提交固定业务任务。 */
    SchedulerView submit(UUID adaptationId);

    /** 查询同一业务键的任务与取消屏障。 */
    SchedulerView find(UUID adaptationId);

    /** 建立持久取消屏障并取消已存在的任务。 */
    SchedulerView cancel(UUID adaptationId);
}
