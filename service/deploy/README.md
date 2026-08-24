# 最小部署与 Schema 初始化

本目录仅管理新服务，不修改 MyTools、DownloadBot、MsgService 的旧数据库和启动方式。所有新 HTTP 服务默认绑定 `127.0.0.1`，Gateway 路由、迁移适配器、邮件拉取、OneBot、PikPak、DSH RPC 和自动化 relay 默认关闭。

所有新服务统一部署在 `/opt/yuyutian/mytools`，不得分散到其他 `/opt` 目录。目录约定如下：

```text
/opt/yuyutian/mytools/
├── releases/          # 不可变版本及 current 软链接
├── config/            # 仓库外环境文件和非敏感配置
├── runtime/tasks/     # Executor 临时工作目录
├── data/downloads/    # 下载落盘目录
├── data/storage/      # Storage Gateway 托管根目录
├── migration/         # 受控迁移快照和对账报告
└── logs/              # 服务日志
```

数据库文件、附件和迁移快照不得放进 `releases/`，发布新版本时只替换 `current` 链接，不覆盖 `data/`、`migration/` 和 `logs/`。旧服务原有目录保持原状。

## 文件

- `services.json`：新服务端口、Schema 和数据库变量前缀的权威清单。
- `env.example`：不包含真实凭据的最小环境变量模板。
- `initialize_schemas.py`：创建独立 Schema、独立账号和授权；不会访问旧 Schema。

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

## 启动顺序

1. MySQL 与 Storage provider。
2. Task Scheduler。
3. Storage Gateway、Identity、Asset Registry。
4. Drive、Reader、Messaging、Media Library、App Catalog。
5. Task Executor。
6. 默认关闭的迁移适配器和外部 connector。
7. MyTools Gateway，所有新路由保持关闭。

空库启动和健康检查完成后才能执行旧数据快照。不可再生数据的正式迁移顺序以 `design/25-implementation-status-and-completion-plan.md` 为准。
