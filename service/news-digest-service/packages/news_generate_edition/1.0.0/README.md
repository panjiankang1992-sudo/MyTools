# news_generate_edition 1.0.0

生成一份已冻结运行参数的日报。仅使用 Python 3.12 标准库，无安装依赖。

- 参数严格只允许 `runId`；执行器任务名 `news_generate_edition`、步骤名 `generate`。
- `NEWS_SERVICE_URL` 默认 `http://127.0.0.1:23350`；跨主机必须 HTTPS，禁止继承代理或跟随跳转。
- `NEWS_JOB_TOKEN` 为至少 32 字符的独立节点作业令牌，不能复用地区、管理、用户代理或 Scheduler 令牌。
- `TASK_EXECUTION_ID` 与 `TASK_LEASE_TOKEN_FILE` 必须由 Executor 注入；租约只在请求内存中传递，不写入结果或日志。
- News 在线向 Scheduler 核验当前任务、业务标识、节点、租约和 fence；生成后再次验证，再原子保存日报与 READY 状态。
- 输出仅 `runId`、`editionId`、`status=READY`。不包含原文、owner、租约或消息目的地；READY 仅表示内容可读。
- 截止前的采集与地区观测由既有内容链路提供，本包不触发截止后的补采来填充旧窗口。

此目录是待发布源码。Scheduler V173 中任务和集群默认关闭；装配校验、不可变发布、节点网络能力及真实 MySQL/任务联调验收后方可启用。不得在部署后原地修改 1.0.0。
