# 最小部署与 Schema 初始化

本目录仅管理新服务，不修改 MyTools、DownloadBot、MsgService 的旧数据库和启动方式。所有新 HTTP 服务默认绑定 `127.0.0.1`，Gateway 路由、迁移适配器、邮件拉取、OneBot、PikPak、DSH RPC 和自动化 relay 默认关闭。

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

输出包含每个服务的 `.service`、`mytools-services.target`、目录配置、日志轮转配置及其 timer。默认 target 不包含迁移适配器、OneBot、PikPak、DSH RPC 和消息自动化；这些能力只能单独显式启用。部署时将服务单元、target 和 timer 安装到 `/etc/systemd/system/`，将 `mytools.conf` 安装到 `/etc/tmpfiles.d/`，将 `mytools-services.logrotate` 安装为 `/etc/logrotate.d/mytools-services`。执行 `systemd-tmpfiles --create /etc/tmpfiles.d/mytools.conf` 后启用 `mytools-logrotate.timer`，最后启动服务 target。

Java 发布包统一命名为 `releases/current/apps/<service>.jar`，Python 服务安装在 `releases/current/venv`。所有服务读取 `/opt/yuyutian/mytools/config/services.env`，该文件必须位于仓库外并限制为部署账号可读。systemd 单元不会限制业务数据必须位于部署根目录，但部署前必须由管理员为 `mytools` 账号授予所配置数据目录的最小读写权限。

## 日志保留

所有微服务的标准输出和错误输出合并写入 `/opt/yuyutian/logs/mytools/<service-name>/service.log`，服务之间不共享目录或文件。轮转规则同时满足两个上限：按天轮转并只保留当前文件加 9 份历史，`maxage 10` 删除超过 10 天的日志；单文件达到 10 MiB 时提前轮转，因此单个微服务未压缩日志总量上限约为 100 MiB。历史日志启用压缩，每分钟 timer 会检查一次大小，缩短在日轮转间隔内超过容量上限的窗口。高日志量时优先满足容量限制，可能保留不足 10 天；低日志量时最多保留最近 10 天。

部署后先保持全部 Gateway 新路由关闭，并运行以下验收。默认检查清单中的 19 个服务、Scheduler 中至少一个在线 Executor，以及 App Catalog 新路由返回 `GATEWAY_002`。若远程主机没有启动默认关闭的适配器，可增加 `--skip-default-disabled`；正式启用任一新 Gateway 路由后，应增加 `--skip-gateway-default-off`，并改为执行对应业务路由的专项验收。

```bash
python3 /opt/yuyutian/mytools/releases/current/deploy/verify_deployment.py \
  --host 127.0.0.1
```

`copytruncate` 允许 Java 和 Python 进程保持打开的 stdout 文件描述符而无需逐个重启。日志目录和文件分别使用 `0750`、`0640`，仅 `mytools` 账号和同组进程可读。

## 启动顺序

1. MySQL 与 Storage provider。
2. Task Scheduler。
3. Storage Gateway、Identity、Asset Registry。
4. Drive、Reader、Messaging、Media Library、App Catalog。
5. Task Executor。
6. 默认关闭的迁移适配器和外部 connector。
7. MyTools Gateway，所有新路由保持关闭。

空库启动和健康检查完成后才能执行旧数据快照。不可再生数据的正式迁移顺序以 `design/25-implementation-status-and-completion-plan.md` 为准。
