# 最小部署与 Schema 初始化

本目录仅管理新服务，不修改 MyTools、DownloadBot、MsgService 的旧数据库和启动方式。所有新 HTTP 服务默认绑定 `127.0.0.1`，Gateway 路由、迁移适配器、邮件拉取、OneBot、PikPak、DSH RPC 和自动化 relay 默认关闭。

rclone RC 作为 Storage/Drive 的本机外部依赖时，其进程环境文件统一放在
`/opt/yuyutian/mytools/config/rclone-rc.env`，权限设为 `0600`。受网络限制的 Provider 可在该文件配置
`ALL_PROXY`、`HTTP_PROXY`、`HTTPS_PROXY` 和包含 `127.0.0.1,localhost` 的 `NO_PROXY`；代理只影响
rclone 的远端出站连接，Storage/Drive 仍通过受认证的回环 RC 调用。rclone remote 配置属于业务连接配置，
可以继续位于既有 DownloadBot 数据目录，不要求随发布版本移动。

以下绝对路径均指远程部署主机，不是开发机本地路径。所有新服务在远程主机统一部署到 `/opt/yuyutian/mytools`，不得分散到其他 `/opt` 目录。目录约定如下：

```text
/opt/yuyutian/mytools/
├── releases/          # 不可变版本及 current 软链接
├── config/            # 仓库外环境文件和非敏感配置
├── runtime/tasks/     # Executor 临时工作目录
├── migration/         # 受控迁移快照和对账报告
```

日志不放在部署根目录，统一写入 `/opt/yuyutian/logs/mytools/<service-name>/service.log`。

数据库文件、附件和迁移快照不得放进 `releases/`，发布新版本时只替换 `current` 链接，不覆盖 `migration/` 和独立日志目录。旧服务原有目录保持原状。

下载目标、媒体扫描目录、电子书目录和 Storage provider 根目录属于业务数据位置，与部署目录无关。它们应在 `services.env` 中指向实际数据盘、NAS 或远程存储挂载点；部署工具不创建、不移动也不删除这些目录。`READER_EBOOK_STORAGE_ROOT` 是 Storage Gateway 中的逻辑根名称，不是 `/opt/yuyutian/mytools` 下的物理路径。

## 文件

- `services.json`：新服务端口、Schema 和数据库变量前缀的权威清单。
- `env.example`：不包含真实凭据的最小环境变量模板。
- `initialize_schemas.py`：创建独立 Schema、独立账号和授权；不会访问旧 Schema。
- `apply_python_migrations.py`：校验并执行 Python 服务的版本化 SQL，记录不可变校验和。
- `generate_systemd_units.py`：根据清单生成服务单元、默认启动 target 和目录配置，不直接安装或启动。
- `assemble_release.py`：在构建机打包 Java 服务、Python 项目、Executor SDK、任务包和部署工具，并生成逐文件 SHA-256 清单。
- `install_release.py`：在远程主机精确验签发布清单，创建版本内 Python venv，全部安装成功后原子切换 `releases/current`。
- `create_service_env.py`：在远程生成只写一次的私有数据库密码、内部令牌和默认关闭配置，不回显秘密。
- `prepare_runtime_directories.py`：精确创建清单声明的运行和微服务日志目录，不修改现有父目录及业务数据目录。
- `monitoring/task-reliability-alerts.yml`：Scheduler/Executor Prometheus 可靠性告警规则；发布包同时携带对应运行手册。
- `monitoring/task-reliability-dashboard.json`：可导入 Grafana 的任务运行与可靠性仪表盘，导入时选择采集 MyTools 管理端点的 Prometheus 数据源。

## 初始化

复制模板到仓库外，填写管理员凭据和每个新服务的独立数据库密码。先执行只读校验：

```bash
uv run --no-project --python 3.12 --with pymysql python \
  service/deploy/initialize_schemas.py --env-file /path/to/mytools-services.env
```

确认连接目标是新的数据库实例或明确的新 Schema 宿主后，再显式增加 `--apply`：

```bash
uv run --no-project --python 3.12 --with pymysql python \
  service/deploy/initialize_schemas.py --env-file /path/to/mytools-services.env --apply
```

工具只执行 `CREATE DATABASE IF NOT EXISTS`、服务账号创建/密码同步和新 Schema 授权。它不包含 `DROP`、`DELETE`、`TRUNCATE`，也不授予旧 Schema 权限。各 Java 服务启动时由 Flyway 在自己的新 Schema 内建表；Python 服务按各自 README 的迁移命令建表。

Schema 与账号初始化后，先验证全部 Python 迁移文件：

```bash
python3 service/deploy/apply_python_migrations.py
```

确认计划后再连接新 Schema 执行：

```bash
uv run --no-project --python 3.12 --with pymysql python \
  service/deploy/apply_python_migrations.py \
  --env-file /path/to/mytools-services.env --apply
```

迁移工具只选择 `services.json` 中声明为 Python runtime 的新 Schema，并使用各服务自己的数据库账号。每个版本写入 `mytools_schema_history`；已经执行的文件若校验和改变会立即失败。工具拒绝 `CREATE/DROP DATABASE`、`USE`、`TRUNCATE TABLE` 和 `DELETE FROM`，旧 Schema 不在连接或授权范围内。

## 生成启动编排

在构建机生成并审阅 systemd 文件：

```bash
python3 service/deploy/generate_systemd_units.py --output /tmp/mytools-systemd
```

全量验证通过后，在构建机装配版本目录。`release-id` 只允许英文、数字、点、下划线和横线；输出目录必须不存在：

```bash
python3 service/deploy/assemble_release.py \
  --release-id 20260824_01 \
  --output /tmp/mytools-release-20260824_01
```

将整个目录传输到远程临时位置后，先只校验清单，再显式安装。安装工具只允许目标根为远程 `/opt/yuyutian/mytools`，不会读取或修改旧 MyTools、DownloadBot、MsgService 目录：

```bash
python3 /tmp/mytools-release-20260824_01/deploy/install_release.py \
  --source /tmp/mytools-release-20260824_01

sudo python3 /tmp/mytools-release-20260824_01/deploy/install_release.py \
  --source /tmp/mytools-release-20260824_01 \
  --python /usr/bin/python3 \
  --execute
```

安装中断时删除未完成的新版本目录且不切换 `current`；已经存在的版本目录禁止覆盖。发布清单不包含 venv，venv 由远程 Python 从随版本携带的五个项目源码建立。

安装后以 `mytools` 账号生成首次环境文件。下载、Storage 和媒体目录参数必须是远程主机上的独立绝对业务路径，工具会拒绝 `/opt/yuyutian/mytools` 和 `/opt/yuyutian/logs/mytools` 下的路径；Reader 参数是 Storage Gateway 逻辑根名称：

```bash
sudo -u mytools python3 /opt/yuyutian/mytools/releases/current/deploy/create_service_env.py \
  --download-root /data/mytools/downloads \
  --storage-root /data/mytools/storage \
  --media-root /data/media \
  --reader-storage-root managed \
  --execute
```

环境文件固定写入远程 `/opt/yuyutian/mytools/config/services.env`，权限为 `0600`，存在时拒绝覆盖。所有 Gateway 路由、迁移适配器和外部连接默认关闭；管理员数据库凭据和旧库凭据不写入该文件。

新环境文件会为 Executor、运维入口、根工程和五个领域服务分别生成 Scheduler 独立令牌。Scheduler 只要发现对应凭据映射中存在非空值，就要求请求同时携带匹配的 `X-Task-Service-Id` 和专属令牌；不同服务的令牌不可复用。既有部署滚动迁移时可暂时保留 `TASK_INTERNAL_TOKEN` 和 `TASK_BUSINESS_INTERNAL_TOKEN` 作为空映射场景的回退，但不得在已经启用独立映射后继续依赖共享令牌；回滚时应恢复整份旧环境文件，不能只回滚单个服务令牌。

注册邮件迁移默认生成 `MESSAGING_REGISTRATION_MAIL_MODE=LEGACY` 和 0% 灰度，同时生成独立的稳定路由密钥及 AES-256-GCM Outbox 密钥。真实灰度必须先执行根服务数据库迁移并验证 Messaging 邮件链路，再逐步调整 `CANARY` 百分比；路由密钥在灰度期间不得轮换，否则同一邮箱可能改变路径。加密密钥轮换前必须排空注册邮件真实投递 Outbox，回滚时也不得删除已提交记录。

输出包含每个服务的 `.service`、`mytools-services.target`、目录参考配置、日志轮转配置及其 timer。默认 target 不包含迁移适配器、OneBot、PikPak、DSH RPC 和消息自动化；这些能力只能单独显式启用。部署时将服务单元、target 和 timer 安装到 `/etc/systemd/system/`，将 `mytools-services.logrotate` 安装为 `/etc/logrotate.d/mytools-services`。远程现有 `/opt/yuyutian` 父目录不是 root 所有，不能使用 `systemd-tmpfiles` 跨所有者创建子目录；应执行以下精确目录准备命令，再启用 `mytools-logrotate.timer` 和服务 target：

```bash
sudo python3 /opt/yuyutian/mytools/releases/current/deploy/prepare_runtime_directories.py --execute
```

Java 发布包统一命名为 `releases/current/apps/<service>.jar`，Python 服务安装在 `releases/current/venv`。所有服务读取 `/opt/yuyutian/mytools/config/services.env`，该文件必须位于仓库外并限制为部署账号可读。systemd 单元不会限制业务数据必须位于部署根目录，但部署前必须由管理员为 `mytools` 账号授予所配置数据目录的最小读写权限。

## 日志保留

所有微服务的标准输出和错误输出合并写入 `/opt/yuyutian/logs/mytools/<service-name>/service.log`，服务之间不共享目录或文件。轮转规则同时满足两个上限：按天轮转并只保留当前文件加 9 份历史，`maxage 10` 删除超过 10 天的日志；单文件达到 10 MiB 时提前轮转，因此单个微服务未压缩日志总量上限约为 100 MiB。历史日志启用压缩，每分钟 timer 会检查一次大小，缩短在日轮转间隔内超过容量上限的窗口。高日志量时优先满足容量限制，可能保留不足 10 天；低日志量时最多保留最近 10 天。

部署后先保持全部 Gateway 新路由关闭，并运行以下验收。默认检查清单中的 19 个服务、Scheduler 中至少一个在线 Executor、Scheduler/Executor Prometheus 端点中的 15 个运行与可靠性指标及正确 `application` 标签，以及 App Catalog 新路由返回 `GATEWAY_002`。若远程主机没有启动默认关闭的适配器，可增加 `--skip-default-disabled`；正式启用任一新 Gateway 路由后，应增加 `--skip-gateway-default-off`，并改为执行对应业务路由的专项验收。

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_deployment.py \
  --host 127.0.0.1
```

基础健康检查通过后，运行无业务副作用的 Scheduler/Executor 验收。该命令创建成功、失败、超时和取消四个任务实例，校验对应终态、终端步骤结果以及幂等键重放，不读取或修改业务 schema。执行前需确认 `system_executor_acceptance/1.0.0` 已发布到远程 `TASK_EXECUTOR_SCRIPT_ROOT`；一次完整执行约需 30 秒。

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_task_execution.py \
  --scheduler-url http://127.0.0.1:23410 \
  --service-id mytools-service
```

验收器默认从当前环境的 `TASK_BUSINESS_MYTOOLS_TOKEN` 读取业务令牌并发送服务身份；不要通过命令行参数暴露生产令牌。启动服务前还应以 `mytools` 账号验证环境文件权限、独立令牌、非 root 门禁、工作目录和脚本索引：

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_task_runtime_config.py \
  --env-file /opt/yuyutian/mytools/config/services.env \
  --require-runtime-paths
```

Linux、双 Scheduler、真实 MySQL、告警和注册邮件灰度的完整步骤见 `docs/runbooks/task-scheduling-release-acceptance.md`。

命令输出包含四个任务实例 ID，可作为部署验收证据保留。该验收只证明控制面和执行面的终态链路；Storage、Drive、Media 和 Reader 的可再生数据仍需执行各自的重建任务并保存领域对账结果。

领域重建通过各服务的创建接口触发后，将返回的 Storage operation ID、Drive/Media operation ID 和 Reader rebuild ID 填入单独的证据 JSON。模板 `domain-rebuild-evidence.example.json` 中的 UUID 仅为格式示例，不可直接执行。验收器从当前进程环境读取四个内部令牌，同时核对领域终态、Scheduler 任务及每个步骤；Media 还会分页确认不存在暂存扫描、运行中或失败分析。

```bash
set -a
. /opt/yuyutian/mytools/config/services.env
set +a
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_domain_rebuilds.py \
  --evidence /opt/yuyutian/mytools/migration/domain-rebuild-evidence.json
```

证据文件只能保存业务 ID 和所有者 ID，不得保存令牌、Provider 凭据或业务数据路径。Storage、Media 的扫描根目录分别由创建操作请求和远程业务配置提供，与 `/opt/yuyutian/mytools` 部署根目录无关。

不可再生数据迁移使用 `migration-plan.example.json` 复制出远程操作计划。计划必须引用已经存在的备份清单绝对路径及其 SHA-256，任务严格按数组顺序串行执行。先去掉模板值、填写每个任务的冻结高水位和结果断言，再进行只读校验：

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/run_migration_plan.py \
  --plan /opt/yuyutian/mytools/migration/migration-plan.json
```

校验通过后才增加 `--execute`。执行模式要求显式指定证据文件；任一任务、步骤或结果断言失败即停止后续阶段。输出证据只保存任务 ID、结果摘要和已验证断言，不复制迁移参数、源路径或凭据：

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/run_migration_plan.py \
  --plan /opt/yuyutian/mytools/migration/migration-plan.json \
  --execute \
  --evidence /opt/yuyutian/mytools/migration/migration-evidence.json
```

同一个 `runId` 会生成稳定的 Scheduler 幂等键；重跑时复用原任务实例，不产生重复业务记录。正式导入应先用单独的 `runId` 执行 `dryRun=true` 计划，确认拒绝数为零，再执行 `dryRun=false` 计划。上述路径均为远程主机路径。

`copytruncate` 允许 Java 和 Python 进程保持打开的 stdout 文件描述符而无需逐个重启。日志目录和文件分别使用 `0750`、`0640`，仅 `mytools` 账号和同组进程可读。

## 可靠性指标与告警

Task Scheduler 与 Task Executor 均在回环管理端点暴露 `/actuator/prometheus`，并通过 `application` 标签区分服务。Prometheus 应从同机或受控管理网络采集，禁止将管理端点直接暴露到公网。

发布包还包含 `operator-tools/`：其中的 `cutover_preflight.py` 和 `audiobook-analysis/` 仅用于受限部署主机上的有声书验收。它们不包含凭证、样书、生成音频或生产配置；质量门禁证据文件必须由部署操作者在受控目录另行创建，不能放入发布包。会产生供应商费用的 POC 工具不随发布包分发。

将发布包中的 `deploy/monitoring/task-reliability-alerts.yml` 加载到 Prometheus 规则目录。规则覆盖 Outbox 超龄与死信、集群不可用导致任务阻塞、队列持续等待、执行租约丢失、Executor Journal 不可读、待上报长期未收敛、人工诊断记录以及持续上报重试。默认 Outbox 超龄阈值与 `TASK_OUTBOX_BACKLOG_ALERT_SECONDS=300` 一致，无可用节点等待时间与 `TASK_NO_AVAILABLE_NODE_ALERT_SECONDS=60` 一致；修改运行参数时应同步审阅告警规则。

将 `deploy/monitoring/task-reliability-dashboard.json` 导入 Grafana，并在导入提示中选择对应 Prometheus 数据源。仪表盘默认展示最近 6 小时并每 30 秒刷新，覆盖队列深度与等待、执行吞吐与平均耗时、失租、Outbox、集群阻塞和 Executor Journal；查询只使用固定服务与终态标签，不引入任务、执行、节点或业务标识等无界标签。

处置步骤见发布包中的 `deploy/monitoring/task-reliability-alerts.md`。告警恢复不得通过删除 Journal、工作目录或 Outbox 记录实现。

## 启动顺序

1. MySQL 与 Storage provider。
2. Task Scheduler。
3. Storage Gateway、Identity、Asset Registry。
4. Drive、Reader、Messaging、Media Library、App Catalog。
5. Task Executor。
6. 默认关闭的迁移适配器和外部 connector。
7. MyTools Gateway，所有新路由保持关闭。

空库启动和健康检查完成后才能执行旧数据快照。不可再生数据的正式迁移顺序以 `design/25-implementation-status-and-completion-plan.md` 为准。
