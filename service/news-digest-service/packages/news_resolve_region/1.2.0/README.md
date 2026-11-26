# news_resolve_region 1.2.0

保留 1.1.0 的活动执行租约、固定报告身份和重试行为，新增免密钥 `IPWHOIS` 供应商。旧版本目录不修改。

自用可配置 `NEWS_REGION_PROVIDER=IPWHOIS`，无需 MaxMind 账号或城市数据库。`NEWS_REGION_ADDRESS_FAMILY` 仍明确选择 IPV4 或 IPV6；报告仅在实际出口与指定地址族一致时有效。公网地址从 `https://ipwho.is/` 取得，再按同一地址查询地区，最后复核出口；原始 IP 只在进程内存中使用。

供应商不返回 GeoNames 城市编号和精度半径，因此保持这两个字段为空。News 按已经登记的省级来源包显示 `PROVINCE_FALLBACK`，不会将城市名称当作精确定位。MaxMind 两种模式继续可选，原有校验保留。

参数仍只有 jobId，任务为 news_resolve_region/resolve；凭据和供应商配置来自 Executor 环境。报告 ID 固定为 execution ID，已有报告不会因重试刷新时间。STORED 仅证明报告接收，不代表本地新闻足量。

Scheduler V177 将原 1.1.0 步骤升级为 1.2.0，并保持任务和集群原有启停状态。配置文件改变后仍需部署对应 News、Scheduler 与任务包；运行主机的诊断预览不等于 Scheduler 实际执行验收。

接口依据：[IPWhois 官方文档](https://ipwhois.io/documentation)。
