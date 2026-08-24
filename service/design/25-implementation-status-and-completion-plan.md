# 服务化实现状态与完成计划

## 1. 审计结论

截至 2026-08-24，目标架构中的服务边界、独立 schema、任务控制面、脚本执行面和主要领域迁移代码均已落地。当前工作不再是继续拆更多微服务，而是把已经存在的服务可靠地部署、迁移旧数据并完成真实环境验收。

未完成项分为三类：

1. **工程收尾**：部署参数、建库建账号和跨服务健康验收已统一；仍需在远程主机复验日志轮转和正式发布布局。
2. **环境验收**：旧库、附件目录、IMAP、OneBot、PikPak、rclone 等真实环境数据尚未在本地审计环境执行迁移和对账。
3. **可延后能力**：DSH 全量 RPC/WebSocket/SSE 替换、存储协议原生移动等不影响当前数据保全和主流程，暂不扩大范围。

旧 MyTools、DownloadBot、MsgService 继续作为迁移期只读权威来源。新链路默认关闭，迁移与校验完成前不删除旧表、旧文件或旧服务。

## 2. 当前实现矩阵

| 层级 | 服务 | 已完成 | 剩余工作 |
| --- | --- | --- | --- |
| 接入层 | MyTools Gateway | Identity、Reader、Drive、Download、Media、Messaging、App Catalog、DSH 路由均可配置且默认关闭 | 部署后逐路由烟雾验证；数据验收后直接启用 |
| 控制面 | Task Scheduler | 任务、步骤、定时/即时、多节点广播/分片、集群节点多对多、实例查询/取消、父子任务和结果接口 | 部署数据库与执行节点后做端到端创建、领取、取消和超时验证 |
| 执行面 | Task Executor | 脚本包、不可变发布、环境契约、DML 授权、日志、心跳、取消与回调；空 Schema 联调已完成节点注册和七个执行集群绑定 | 远程执行节点凭据注入和长任务故障验证 |
| 基础层 | Storage Gateway | provider、对象、传输、校验、可恢复 `MOVE_TREE` 任务 | 配置真实本地/rclone/WebDAV/S3 provider 并对账；协议原生移动可延后 |
| 基础层 | Asset Registry | 资产、来源、哈希、资源包和任务写回 | 执行 `local_file` 快照导入与文件存在性/哈希抽检 |
| 业务层 | Download Ingestion | HTTP、磁力、消息附件、X、网页归档、本地导入及下载后入库 | DownloadBot/PikPak 真实账号环境回放与对账 |
| 业务层 | Media Library / Intelligence | 媒体扫描、标签、探测、缩略图、故事板、简介及任务包 | 旧媒体数据导入、目录扫描和可再生分析数据重建 |
| 业务层 | Reader | 书架、书源、搜索、章节、导入、发现和缓存任务 | 旧书架/书源导入；外部书源少量真实查询验证 |
| 业务层 | Messaging / Automation | 邮件域模型、消息投递、规则、动作、OneBot 连接及任务化处理 | MsgService SQLite/附件导入、IMAP 隔离收发、规则回放对账 |
| 业务层 | Identity | 用户、角色、会话、验证码和迁移门禁 | 旧用户/角色/验证码导入与数量、主键、登录抽检 |
| 业务层 | Drive | 账户、目录索引、文件操作和访问票据 | 旧网盘配置迁移、provider 绑定和索引重建 |
| 业务层 | App Catalog | 应用、版本、文件关系和迁移门禁 | 旧应用数据导入与文件引用对账 |
| 连接层 | DSH Connector | 会话绑定、连接状态、迁移门禁 | 会话数据导入；全协议切换按实际使用需要延后 |

## 3. 已验证基线

本地验证使用 Java 21 与 Python 3.12，且未连接生产数据库或外部账号。

| 验证项 | 结果 |
| --- | --- |
| 14 个 Java Maven 服务执行 `mvn -q test` | 全部通过 |
| 5 个 Python 在线/适配服务 | 91 个测试通过 |
| Media Intelligence 脚本包 | 12 个测试通过 |
| Java 测试源码 | 97 个 |
| Python `test_*.py` 测试源码 | 105 个 |
| 数据库迁移 SQL | 224 个 |
| Executor 脚本发布与环境契约 | 已有装配和门禁测试覆盖 |
| MySQL 8.4 全新 Schema 启动 | 17 个独立 Schema 完成迁移，19/19 个服务健康 |
| Scheduler/Executor 基础联通 | Executor 为 `ONLINE`，已绑定 7 个执行集群 |
| Gateway 默认关闭 | App Catalog 路由返回 HTTP 503 和 `GATEWAY_002` |
| 部署工具测试 | 30 个测试通过，包含建库、Python 迁移、systemd 生成、部署验收、迁移编排和领域重建证据校验 |

统一复验入口：

```bash
python3 service/scripts/verify_service_architecture.py \
  --java-home /opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home
```

该命令使用 `uv --no-project` 提供隔离的 Python 3.12 测试环境，不会在各服务目录生成锁文件或安装产物。可用 `--skip-java`、`--skip-python` 分组执行，也可先用 `--dry-run` 检查命令计划。

## 4. 重新安排的完成顺序

### 阶段 A：统一验证与部署准备

- 固化全服务验证入口并在每次迁移前后执行。
- 为所有服务补齐环境变量样例、schema 创建顺序和最小启动编排。
- 启动时只执行新 schema 迁移，不修改或删除旧 schema。

完成标准：所有服务能使用空的新 schema 启动；默认关闭的 Gateway 路由和旧系统行为不变。

该阶段已在一次性 MySQL 8.4 容器中完成本地实测。17 个新 Schema 独立初始化并执行全部 Java/Python 迁移，19 个有状态和无状态服务均返回健康；Executor 注册为在线节点并加入 asset、download、media、messaging、reader、reader-probe-orchestration、storage 集群；Gateway 默认关闭检查返回 `GATEWAY_002`。远程部署后使用 `service/deploy/verify_deployment.py` 重复同一验收，不把本地临时路径作为远程部署或业务数据路径。

### 阶段 B：迁移不可再生数据

按依赖顺序执行：

1. Identity 用户、角色和认证主体。
2. Storage provider、Drive 账户和访问配置。
3. Asset 元数据及文件来源关系。
4. Media 人工标签、目录关系和播放状态。
5. Reader 书架、书源和阅读状态。
6. Messaging 邮件、附件、规则及动作状态。
7. App Catalog、Feedback 与 DSH 会话关系。

每一步先生成只读快照和备份清单，再导入新 schema，最后执行数量、主键、关联与文件存在性对账。失败时保留目标库现场并重新生成目标 schema，不回写旧库。

远程执行使用 `service/deploy/run_migration_plan.py`。它在创建任何任务前强制核对备份清单 SHA-256，并按 Identity、Storage/Drive、Asset、Media、Reader、Messaging、App/Feedback/DSH 的计划数组顺序串行等待 Scheduler 终态；任务、步骤或领域结果断言任一失败就停止。证据只保留任务标识、结果摘要和通过的断言，不落盘迁移参数及业务路径。

完成标准：不可再生记录全部可在新服务查询；所有差异均有可解释清单；备份文件通过内容门禁。

### 阶段 C：重建可再生数据

- 创建 Storage/Drive 索引、Media 分析、Reader 缓存和搜索索引任务。
- 由 Scheduler 创建实例，Executor 执行脚本，并验证超时、失败、取消步骤。
- 使用幂等键重放，确认重复执行不会产生重复业务记录。

完成标准：任务状态、步骤日志、领域结果和父子任务关系可追踪，重放结果一致。

### 阶段 D：真实连接与端到端验收

- 分别使用隔离测试账号验证 IMAP、OneBot、PikPak、rclone/WebDAV/S3 和外部书源。
- 验证“在线接口创建任务 → Scheduler 分配 → Executor 执行 → 领域服务回写 → Gateway 查询”的完整链路。
- 验证执行节点中断、任务取消、步骤超时以及失败补偿。

完成标准：核心任务类型至少各有一次成功和一次受控失败记录，且旧数据未被修改。

### 阶段 E：启用新入口并保留回退材料

当前没有活跃用户，无需设计灰度、双写或复杂切流。数据对账通过后直接启用对应 Gateway 路由，保留旧服务、数据库备份和附件快照一段观察期；确认数据完整后再单独决定是否下线旧服务。

## 5. 明确不在本轮扩大范围的事项

- 不把每一种脚本再拆成独立常驻微服务。
- 不引入 Kubernetes、服务网格或额外消息中间件作为完成前置条件。
- 不实现无使用证据的 DSH 全协议替换。
- 不因目标数据可重新生成而删除旧库或旧文件。
- 不在仓库保存生产密码、Cookie、Token、邮箱授权码或 rclone 配置内容。

## 6. 后续验收证据

本地测试通过只能证明代码基线，不等价于生产迁移完成。阶段 B 至 D 必须在具备真实数据库、文件快照和外部账号的环境执行，并保留以下证据：

- 迁移前备份清单及校验摘要。
- 各表源记录数、目标记录数、跳过数和失败数。
- 文件存在性与抽样哈希结果。
- 任务实例、步骤日志、结果和失败补偿记录。
- Gateway 启用前后的核心 API 烟雾测试结果。
