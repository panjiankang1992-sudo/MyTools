package com.yuyutian.mytools.task.scheduler.model;

import java.util.UUID;

/** 业务派发查询仅返回身份和状态，不暴露任务参数或执行凭据。 */
public record ReaderAdaptationDispatchView(UUID adaptationId, UUID taskInstanceId,
                                           boolean cancellationRecorded, String taskStatus) {
}
