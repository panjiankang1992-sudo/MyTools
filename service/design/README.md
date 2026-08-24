# MyTools 服务化架构设计索引

本目录描述 MyTools、DownloadBot 与 MsgService 重构后的目标架构。设计采用“在线领域服务 + 任务控制面 + 脚本执行面”，所有耗时且非直接数据查询操作均任务化。

## 文档清单

| 文档 | 服务/主题 |
| --- | --- |
| [00-overall-architecture.md](00-overall-architecture.md) | 总体架构、分层、服务边界、迁移总路线 |
| [01-mytools-gateway.md](01-mytools-gateway.md) | MyTools Gateway / BFF |
| [02-identity-service.md](02-identity-service.md) | 用户、认证、权限与验证码 |
| [03-messaging-service.md](03-messaging-service.md) | 邮件、QQ、Telegram、OneBot 统一消息服务 |
| [04-message-automation-service.md](04-message-automation-service.md) | 消息规则与业务自动化编排 |
| [05-task-scheduler-service.md](05-task-scheduler-service.md) | 任务定义、实例、步骤、集群、调度和父子任务 |
| [06-task-executor-service.md](06-task-executor-service.md) | 脚本执行、DML、隔离、日志、取消和回调 |
| [07-download-ingestion-service.md](07-download-ingestion-service.md) | 下载请求解析和下载业务生命周期 |
| [08-asset-registry-service.md](08-asset-registry-service.md) | 统一资产、来源、哈希和资源包登记 |
| [09-storage-gateway-service.md](09-storage-gateway-service.md) | 本地文件、rclone、WebDAV/S3/PikPak 存储操作 |
| [10-media-library-service.md](10-media-library-service.md) | 媒体目录、标签、播放和分析业务状态 |
| [11-media-intelligence-service.md](11-media-intelligence-service.md) | 标签、缩略图、截图、媒体探测与简介脚本 |
| [12-drive-service.md](12-drive-service.md) | 网盘账户、索引、文件操作和访问票据 |
| [13-reader-service.md](13-reader-service.md) | 书架、书源、搜索、章节和电子书导入 |
| [14-downloadbot-adapter-service.md](14-downloadbot-adapter-service.md) | DownloadBot 默认关闭的旁路迁移防腐层 |
| [15-msgservice-adapter-service.md](15-msgservice-adapter-service.md) | MsgService 默认关闭的历史数据迁移防腐层 |
| [16-legacy-asset-adapter-service.md](16-legacy-asset-adapter-service.md) | MyTools local_file 一致性快照与只读资产迁移适配器 |
| [17-pikpak-connector-service.md](17-pikpak-connector-service.md) | PikPak 离线提交、稳定性观察与受控移动适配器 |
| [18-onebot-connector-service.md](18-onebot-connector-service.md) | OneBot/NapCat 文件解析、凭据隔离与受控内容流适配器 |
| [21-app-catalog-service.md](21-app-catalog-service.md) | 应用目录、版本和文件关系迁移 |
| [22-dsh-connector-service.md](22-dsh-connector-service.md) | DSH 会话绑定与连接适配 |
| [23-legacy-data-disposition.md](23-legacy-data-disposition.md) | MyTools 旧表逐表分类、备份和数据保全门禁 |

## 共通约束

- 在线接口只同步执行鉴权、轻量校验和直接数据查询；下载、扫描、转换、推理、外部搜索、批处理必须创建任务。
- 任务步骤全部脚本化，执行单位是有版本的脚本包；脚本可以调用任务 API、领域 API，也可在授权范围内执行数据库 DML。
- 每个服务拥有自己的数据，不允许把任务数据库当作业务数据库。
- 跨服务写入默认走领域 API；直接 DML 仅用于明确授权的批处理、迁移、索引和同服务内部数据操作。
- 所有创建任务操作必须支持幂等键，所有事件必须支持重复消费。
- 第一阶段允许共享 MySQL 实例和共享文件系统，但必须先拆分 schema、账号和数据所有权。
