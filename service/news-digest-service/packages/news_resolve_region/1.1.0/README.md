# news_resolve_region 1.1.0

在 1.0.0 的固定出口、MaxMind、HMAC 和本地日志基础上增加 Scheduler 活动执行租约。保留原版本，不原地改写。

- 参数只有 jobId；任务名 news_resolve_region、步骤 resolve。由指定 news.node 节点执行。
- 新工作协议使用 NEWS_JOB_TOKEN，读取 TASK_CONTEXT_FILE、TASK_EXECUTION_ID、TASK_LEASE_TOKEN_FILE。地区供应商和指纹密钥沿用包级配置，不写入 Scheduler 参数。
- 先查询作业回执，已有结果直接返回；否则在实际节点探测，随后使用活动租约提交。报告 ID 固定为执行 ID；同执行日志重放不刷新 observedAt。
- 输出 jobId/observationId/status=STORED/observationStatus。STORED 只代表报告收到；RESOLVED、LOOKUP_FAILED、EGRESS_UNAVAILABLE 分开展示。
- 相同任务的新执行如果已有已提交报告，返回原回执；未提交的旧执行不能替换已提交报告。
- 原始 IP 仍只在内存中存在；接口不接收用户自报 IP、城市或网络目标。网络行为和 MMDB 校验与 1.0.0 相同。

仅为待发布源码。Scheduler V174 默认关闭。开启 NEWS_PIPELINE_ENABLED 时旧静态令牌上报入口关闭，工作节点必须升级到 1.1.0。关闭流水线后可用旧接口独立诊断，但不得把诊断视为自动任务验收。
