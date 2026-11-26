# 每日新闻第十三批：旧环境增补与发布检查

日期：2026-10-18。持续目标保持 **ACTIVE**。本批完成发布工具和完整本地装配，并开始 Ubuntu 只读盘点；没有部署、改变生产配置或发送消息。

## 交付

- `prepare_news_environment.py` 默认只预览键名；显式应用只补缺失项，不覆盖已有值或空值。保留原行和 0600 权限，使用文件锁、替换前复核、私有备份和原子替换。重复执行不轮换密钥。缺项且业务已开启时拒绝自动升级。
- `news_deployment_config.py` 统一独立角色凭据、AES 密钥、内部地址、调度器一致性、节点、集群、执行器门禁、保留策略和 staged/shadow/delivery 条件。
- `verify_news_deployment.py` 验证精确发布清单、必要 JAR 类/迁移、包索引与文件摘要。安装后可单独检查原始载荷和非 root 执行器的本地依赖；此模式本批只做模拟回归，未在 Ubuntu 执行。
- 装配器在最终发布目录出现前强制运行新闻检查；失败时清理临时输出。
- 两个命令从发布目录执行时不生成模块缓存，避免只读检查本身破坏发布清单。
- [发布手册](../../runbooks/daily-news-release.md)补齐增量配置、分阶段检查、安装前后清单差异和现场配置覆盖边界。

## 自动测试

| 测试 | 通过 | 覆盖 |
| --- | ---: | --- |
| NewsConfigurationTest | 9 | 新环境兼容、三阶段条件、凭据复用、地址与节点、配置解析和脱敏 |
| PrepareNewsEnvironmentTest | 7 | 预览、追加、私有备份、幂等、文件竞争、锁和大小上限 |
| VerifyNewsDeploymentTest | 9 | 清单篡改、缺迁移、未索引脚本、安装后载荷、运行依赖边界及真实子进程 CLI |
| NewsOperationsTest | 6 | 既有管理工具回归 |
| AssembleReleaseTest | 4 | 完整清单、不可覆盖、缓存清理及新闻检查失败不发布 |
| 合计 | **35** | 0 失败，0 跳过 |

执行命令：

```bash
/Users/pankang/.local/bin/python3.12 -m unittest discover -s service/deploy -p 'test_*news*.py'
/Users/pankang/.local/bin/python3.12 -m unittest discover -s service/deploy -p 'test_assemble_release.py'
```

## 实际本地装配

完整装配使用 Java 21 对清单内 **18 个 Java 服务执行 Maven package -DskipTests**，并纳入 **7 个 Python 服务、153 个任务包、732 个清单文件**。五个新闻相关 JAR 的必要类/迁移以及所有任务文件、索引摘要检查通过。打包成功不等于全仓库测试通过；本轮没有重复运行所有 Java 服务的测试。

首次完整构建后，只修改发布工具与手册；最终装配复用同一批未改变的 JAR。最终本地验证包为 `/tmp/mytools-news-release-20261018-b13-verified`。源码摘要、JAR 摘要、清单摘要和测试结果保存在 [结构化证据](news-release-gates-test-summary.json)。工作区仍含其他在途修改，该目录是本地验收材料，不代表已审定生产发布。

直接使用最终包中的命令、临时 0600 测试环境执行并通过五项实际检查：预览不修改原文件；应用保留原行；重放不生成新密钥；staged 与精确清单通过；未生成模块缓存。临时配置自动删除，未使用真实账号或目标。成功 JSON 仍明确 `liveAcceptance=false`。

## Ubuntu 只读盘点

从当前本地 SSH 配置选择 Ubuntu 通道，先验证连接，再以授权的本机管理员身份读取服务元数据。普通 SSH 账户无法遍历部署目录，因此最初不可见不能解释为部署不存在；管理员只读检查确认当前发布目录和私有环境文件存在。

Scheduler、Executor、Messaging、Gateway 当前均以非 root 身份运行；新闻 JAR 和新闻 systemd 单元尚未安装。Docker 通过管理员身份可用。现场默认 Python 为 3.14.4，不能假定存在 `/usr/bin/python3.12`。

四个相关服务均加载共享环境；其中 Scheduler、Executor、Gateway 还按顺序叠加其他环境文件。仅验证 services.env 不足以代表最终运行配置。此次只读取 systemd 的文件路径和状态，**未读取生产环境内容**、未启动测试容器、未连接业务数据库、未安装依赖、未改动或重启服务。

## 后续

N24 的本地装配和检查工具已完成，仍保留实际安装依赖、最终配置与回退验收；N25 已开始现场只读盘点，出口定位与城市来源尚未验收。下一步按现场环境覆盖关系核对配置，并准备隔离的真实 MySQL 迁移验证，再推进来源探测和授权渠道验收。七日连续采集与 QQ 平台限制不因本批通过而被标记完成。
