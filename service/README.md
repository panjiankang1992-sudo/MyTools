# MyTools Service Workspace

该目录是 MyTools 服务化迁移的旁路工作区。所有子服务均独立构建，未加入根项目 `pom.xml`，不会改变现有 MyTools、DownloadBot 或 MsgService 的启动、构建和部署。

## 目录

- `design/`：总体及各服务详细设计。
- `contracts/`：跨服务稳定契约和 Schema。
- `scripts/`：仅供新服务工作区使用的验证脚本。
- 各 `*-service/`：独立服务工程。
- `media-intelligence/`：版本化 Python/Shell 任务脚本包。

## 分阶段迁移

1. 建立目录、契约、Scheduler 与 Executor MVP。
2. 旁路双跑媒体标签任务。
3. 迁移下载、媒体处理和 Reader Runtime 耗时任务。
4. 迁移消息、存储、资产及领域服务。
5. 验证无流量后再删除旧路径。

任何阶段都不得直接修改旧服务的生产入口；切流必须有开关、回退路径和结果对账。

新服务统一使用独立数据库 schema。不可再生数据必须迁移；可再生数据在迁移不可靠或成本过高时通过任务重新生成。
