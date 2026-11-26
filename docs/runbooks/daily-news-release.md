# 每日新闻发布准备

个人版当前现场进度优先参考 [2026-10-19 发布准备](../verification/daily-news/2026-10-19-personal-release-preparation.md)：服务器存在 V175 已应用、V173/V174 缺失的顺序，须先核对精确待迁移列表并按已验证路径补齐；不能跳过校验或直接假定普通顺序升级成功。自用配置可用 IPWHOIS。旧的全量发布与长期治理步骤不扩展个人版首发范围。

状态：默认关闭配置、旧环境增量工具、三阶段配置检查和完整本地发布装配已完成验证；隔离 MySQL 的完整迁移与关键 repository 用例已通过。新增运行进程检查，并完成 Ubuntu 宿主机的四个公共来源访问探测；实际 Executor 任务、地区、业务数据库副本与渠道联调仍待验收，不能据此开启每日发送。

## 发布内容

`service/deploy/services.json` 已登记 `news-digest-service`，端口 23350，Schema `mytools_news`，数据库前缀 NEWS，依赖顺序为 Scheduler、Messaging。服务默认不进入启动 target。生成的 systemd 单元使用现有私有环境文件，日志归属独立目录；未在本轮安装或启动实际单元。

News 独立构建，不加入根 Maven 构建。完整发布装配器现会包含 News JAR；其 Flyway V1—V10 位于 JAR 内。Messaging 需要 V18—V22，Scheduler 需要新闻 V173/V174 和出站 V176。目标数据库仍须验证包含所有历史迁移的完整升级路径；针对新迁移的 H2 回归不能替代它。

News、Messaging 和 Scheduler 的当前构建统一使用 Spring Boot 3.5.16、Flyway core/mysql 11.20.3、Java 21。Flyway 两个模块使用相同显式版本；不能仅替换 mysql 扩展，也不能把 Flyway 11 塞进旧 Boot 3.2 启动配置。升级前核对现有 `spring.flyway.*` 覆盖项，尤其不再支持的 clean-on-validation-error；保留默认禁止清库和迁移前校验，不通过 repair 或跳过校验处理历史不一致。

任务包固定为：

| 用途 | 包及版本 |
| --- | --- |
| 生成日报 | news_generate_edition / 1.0.0 |
| 观测执行节点地区 | news_resolve_region / 1.2.0 |
| 冻结消息投递 | message_send_channel_message / 1.0.0 |

`assemble_executor_packages.py` 校验版本、入口、文件摘要和索引；不覆盖已存在的发布目录。安装器在包含 News JAR 的版本中，要求地区包 1.1.0 的 requirements.txt 存在，并将固定的 `maxminddb==3.2.0` 随发布内 Python venv 安装。缺失依赖清单时安装失败，不能切换 current。此项已通过模拟安装调用验证；没有在 Ubuntu 上执行 pip 或发布。

## 环境与凭据

`env.example` 列出配置名和无秘密样例；新闻采集、流水线、调度、投递、清理、Gateway 路由及各定时器均明确关闭。应用内部 timer 默认值与初次部署值有所不同：应用默认 true，但环境模板和新建环境显式 false，方便先做手动验收。

新环境生成器为 News 用户代理、管理、任务回调、旧地区报告、Scheduler 业务身份、地区指纹，以及 Messaging 客户端、worker、admin 分别生成独立随机值。Messaging 目的地加密使用独立 32 字节 AES 密钥，不复用注册邮件密钥。数据库账号按 NEWS 前缀生成。

生成器仅用于**新建**环境，拒绝覆盖已有文件。升级现有环境时，使用 `prepare_news_environment.py` 只补充缺失项，不重建整套 services.env。秘密只存于仓库外受控环境文件，不进入命令参数、截图、日志或验收报告。

Gateway 已内置以下映射，无需另建 YAML：

- `GATEWAY_NEWS_ROUTE_ENABLED` → route-enabled，默认 false。
- `NEWS_SERVICE_URL` → News 地址，默认 `http://127.0.0.1:23350`。
- `NEWS_INTERNAL_TOKEN` → News 用户代理身份，不能复用 admin 或 job 凭据。

公开阅读入口 `NEWS_READER_URL` 只有在实际 HTTPS 地址及 Gateway 路由可用后填写。前端只访问同源 Gateway，内部令牌不得以 VITE_* 形式进入前端构建。

现有 MyTools Nginx 的 `/api/` 若仍转到旧后端 23110，需要在同一 server 块引入 `deploy/nginx/news-api.inc`，单独将 `/api/v1/news/` 转到 Gateway 23200。先核对该站点的前端服务，再检查完整 Nginx 配置和同源登录请求；Vite 开发代理不会进入生产构建。2026-10-19 现场发现原页面上游 23111 未监听，邮件日报上线不依赖恢复该网页。

## 指定执行节点

Executor 默认声明 `news` 集群，Scheduler 默认允许该集群。若现有环境设置了 `TASK_ALLOWED_EXECUTOR_CLUSTERS` 或覆盖了 Executor 集群列表，升级时须保留已有成员并显式加入 news；新默认值不会覆盖已有配置。

`NEWS_REGION_NODE_ID`、该执行器的 `TASK_EXECUTOR_NODE_NAME`、Scheduler 的 `TASK_NEWS_NODE_VALUE` 必须对应同一个已确认注册节点名。TASK_NEWS_NODE_VALUE 默认 absent。节点可以请求声明 news.node，但 Scheduler 只有在注册节点名匹配其配置时才授予实际标签；其他节点不获得此标签，不能借用相同声明领取新闻任务。空值或 absent 不授予标签。

保持 `TASK_NODE_REGISTRATION_POLICY_ENFORCE=true`。先更新并启动 Scheduler 的注册策略，再更新 Executor，避免旧 Scheduler 因不认识 news 集群拒绝整次注册。声明集群不等于启动业务：news 集群与两个新闻任务，以及新版消息投递任务的迁移默认仍为关闭。

## 升级已有环境

将下列路径中的 `NEWS_RELEASE_ID` 替换为本次已校验的发布版本目录；不要把 current 符号链接直接传给文件清单检查。先确认选定的 `python3` 至少为 3.12。以下为操作手册，本地验证没有执行这些生产命令。

```bash
news_release=/opt/yuyutian/mytools/releases/NEWS_RELEASE_ID
sudo python3 "$news_release/deploy/prepare_news_environment.py" \
  --env-file /opt/yuyutian/mytools/config/services.env
sudo python3 "$news_release/deploy/prepare_news_environment.py" \
  --env-file /opt/yuyutian/mytools/config/services.env --apply
```

默认只输出待添加、保留和空必填项的**键名**，不生成密钥、不改文件。`--apply` 仅生成不存在的密钥并追加缺失项；原行、注释、引号、属主、属组和 0600 权限保持，原始内容另存同目录 0600 私有备份 `services.env.news-backup-<UUID>`。重复执行不再生成密钥或备份。现有空值也保留，交给检查器报错；尤其不能因空加密键而自动轮换已有数据的密钥。

环境文件必须是单个普通文件、0600、无硬链接；重复键、复杂引号、CRLF、多行赋值、符号链接或超过 1 MiB 会拒绝。工具不调用 shell 或展开配置值。应用时使用独立文件锁并在替换前复核文件身份和内容；配置维护期间仍须避免其他不遵守该锁的写入程序。已有新闻或新版出站开关开启而配置缺项时，工具拒绝自动增补。先通过业务停用流程收敛，再进行配置维护。

工具不创建数据库、授予权限、登记来源、重启服务或发送消息。新增 NEWS 数据库凭据还须通过已有最小权限 Schema 配置流程落地；仅写入环境文件不会产生可用账号。

## 发布与三阶段检查

```bash
sudo python3 "$news_release/deploy/verify_news_deployment.py" \
  --env-file /opt/yuyutian/mytools/config/services.env \
  --stage staged --release "$news_release"
```

| 阶段 | 配置检查要求 | 不能据此推断 |
| --- | --- | --- |
| staged | 各主业务开关关闭；角色密钥独立且符合长度/AES 要求；保留、时区和地址配置合法 | 数据库账号已建立、服务已上线 |
| shadow | 采集、流水线、订阅调度、News 清理及对应定时器开启；投递关闭；指定节点一致、集群和执行器门禁有效；定位提供方配置齐备 | 节点已注册、任务已启用、来源可达或七日数据完整 |
| delivery | shadow 的运行条件；另要求 News/Messaging 出站、相关定时器、Messaging 清理和 Gateway 新闻路由开启，阅读地址为 HTTPS | 接收目标所有权已核验、消息已送达 |

没有 `--release` 时只验证配置。未安装的发布目录使用精确文件清单：每个文件大小和 SHA-256 匹配，不能混入额外文件或符号链接；再核对五个相关 JAR 中的必要类/迁移、三个固定版本任务包、所有包文件和聚合摘要，以及地区包的固定依赖。完整装配器在最终目录出现前强制执行此检查，失败时清理临时输出。静态清单不提供签名或供应链身份认证，仍须使用受信任的构建来源。

三个迁移服务的 JAR 还须包含单一的 Spring Boot / autoconfigure 3.5.16、Flyway core/mysql 11.20.3。检查器读取内嵌依赖的 Manifest 或 pom.properties 核对版本，拒绝旧版本、混装和只改文件名的情况；`pinnedMigrationRuntimeVerified=true` 只表示该项静态核对通过，不代表应用启动或数据库验收。

发布安装会产生 venv/build 等额外文件。安装后使用下面的 `--runtime` 模式：原清单载荷仍须全部匹配，apps 和 task-packages 禁止混入未发布文件；安装生成的其他文件不冒充原始清单内容。运行依赖检查必须以非 root 执行器身份进行，只检查本地路径、工作目录权限、选定发布 venv 的 Python >=3.12 和 maxminddb==3.2.0、MMDB 文件可读性；不联网，不验证城市库的新鲜度和真实定位。实际地区任务仍会检查城市库类型与 30 天期限。

```bash
sudo systemd-run --wait --pipe --collect --unit=mytools-news-preflight \
  -p User=mytools -p Group=mytools \
  -p EnvironmentFile=/opt/yuyutian/mytools/config/services.env \
  --setenv="TASK_EXECUTOR_SCRIPT_ROOT=$news_release/task-packages" \
  --setenv="TASK_EXECUTOR_PYTHON_EXECUTABLE=$news_release/venv/bin/python" \
  /usr/bin/env python3 "$news_release/deploy/verify_news_deployment.py" \
  --from-environment --stage shadow --release "$news_release" --runtime
```

系统服务管理器读取私有环境后以 mytools 身份执行，避免放宽文件权限；这里不把任何密钥写入命令参数。工作目录和其他配置须与实际 Executor 一致。投递验收阶段将 `shadow` 改为 `delivery`。检查器不是 Spring 最终配置解析器：启动参数、额外配置文件、其他环境别名或 systemd 覆盖项仍须现场核对。多主机部署需要逐机检查对应配置的一致性。

上述瞬时单元命令只适用于标准的单环境文件布局。2026-10-18 的 Ubuntu 只读盘点发现，现有 Scheduler、Executor 和 Gateway 在共享文件之外叠加了多个环境文件。不能把单独检查 services.env 当作这些服务的最终配置验收；后续现场接入需按实际加载顺序合并覆盖项，保留现有配置和脚本根目录选择。此次没有修改环境文件或重启服务。

JSON 的 `configurationValid`、`releaseFilesValid`、`runtimeDependenciesValid` 只说明各自检查通过；`liveAcceptance` 始终为 false。失败只输出固定错误码和允许的配置键名，不输出原值。检查器和增量工具从发布包内执行时禁止生成模块缓存，不改变不可变发布文件清单。

## 已运行进程的只读检查

```bash
sudo python3 "$news_release/deploy/inspect_news_runtime.py"
```

该工具按部署生成器的固定单元名检查 News、Gateway、Scheduler、Executor、Messaging，读取运行进程的环境、命令参数及非 root 身份。读取前后核对 PID 与启动时间，并在五个角色采集完成后再核对一次；服务重启、进程复用或读取失败时放弃整个结果。输出只包含固定角色/配置键名及状态，不包含配置值、IP、路径、参数原文或 Secret 摘要。它不修改环境、数据库、任务或服务，不发起网络请求。

报告区分 `NOT_INSTALLED`、`NOT_RUNNING` 和 `RUNNING`，检查所需环境变量缺项、令牌长度/AES 编码，以及跨进程同名凭据和节点配置是否一致。Executor 的包入口、索引及定位文件仅是候选路径存在性检查：未验证运行用户可读权限、索引完整性、MMDB 类型/期限，也不会执行环境变量指定的解释器。对应字段明确为 false；使用前述发布/运行检查及真实地区任务完成后续验证。

`overrideSignals` 指出命令行、JVM 属性、Spring JSON、额外配置文件、活动 profile 或 JVM 选项环境等覆盖入口。工具不解析其配置值；无覆盖信号也不能排除应用配置文件、环境别名等其他来源。`scope=PROCESS_ENVIRONMENT_ONLY`、`springBindingVerified=false`、`liveAcceptance=false` 固定保留。`missingEnvironmentKeys` 仅表示进程环境缺项，不宣称高优先级配置中也一定缺失。退出码 0 表示检查完成，**不是上线通过**；2 表示无法取得一致快照。

2026-10-18 现场结果：News 单元未安装；另外四个服务以非 root 身份运行，新闻相关环境键未齐备，三个新包入口不存在于 Executor 环境指定的脚本目录；Gateway、Scheduler、Executor 存在命令行覆盖入口。宿主机以 mytools 身份、无隐式代理访问 GitHub、HN、DEV 和中新网 RSS，均取得 HTTP 200 非空响应。固定 ipify IPv4 端点连接失败、IPv6 端点超时；没有可用地区结果。该宿主机探测未进入 Scheduler 租约、Executor 网络策略或生产采集器，不能替代实际任务验收。详细证据在源码仓库 `docs/verification/daily-news/2026-10-18-news-runtime-inspection.md`。

## 真实 MySQL 兼容性测试

`MySqlCompatibilityTest` 在设置 `NEWS_MYSQL_TEST_CONFIG` 后才运行，默认不会连接任何 MySQL。配置文件必须是 0600 普通文件，包含 `host`（固定 127.0.0.1）、临时 `port`、`user`（固定 news_verify）、随机 `password`（至少 32 字符）和 32 位小写十六进制 `marker`。凭据只属于专用测试实例，不能复用 services.env 或业务数据库账号。

专用实例须先建立 `news_verification_control.verification_instance`：`id INT PRIMARY KEY`、`marker CHAR(32) NOT NULL`，其中 id=1 的值与私有配置一致。测试在创建或删除任何 schema 前核对这个标记，每个用例只操作随机的 `news_it_<UUID>`。临时账号需要在该实例中创建和删除测试 schema 的权限；不要在业务实例中创建这组测试账号和标记。

推荐在数据库所在测试主机运行，避免几百个迁移语句受到跨网往返延迟影响。在仓库根目录、已选定 Java 21 的环境执行：

```bash
NEWS_MYSQL_TEST_CONFIG=/secure/isolated-mysql.json \
  mvn -f service/news-digest-service/pom.xml \
  -Dnews.mysql.serviceRoot="$PWD/service" -Dtest=MySqlCompatibilityTest test
```

无法在目标主机进行 Maven 构建时，可在可信构建机生成 test-classes、classes 和测试 classpath，连同三个服务的迁移目录校验 SHA-256 后传输，使用 `com.yuyutian.mytools.news.MySqlCompatibilityLauncher`。该入口使用同一套 JUnit 用例，将计数写入唯一参数指定的 JSON 文件；未发现用例、跳过、初始化失败或测试失败均返回非零状态。设置 `news.mysql.serviceRoot` 指向随包复制的 service 目录，并通过私有环境注入测试配置路径。全部迁移文件必须能被运行用户读取；测试会核对执行数量，不能默许 Flyway 忽略不可读文件。

当前八项用例包含两项新旧引擎兼容测试。另准备 Flyway core/mysql 9.22.3 和 Gson 2.10.1 三个经过摘要校验的旧依赖，放入测试目录，用 `-Dnews.mysql.legacyLibraryDirectory=/path/to/legacy-lib` 指定；本地省略时从默认 `~/.m2/repository` 的对应版本读取。旧 JAR 不进入生产产物。测试在受限子 JVM 中先用旧引擎建立 News V6、Messaging V19、Scheduler V172 历史，再用新引擎接续迁移，比较完整旧历史行；另验证旧校验和被破坏时停止升级且不自动修复。子 JVM 通过私有文件获取临时账号，命令参数没有凭据，超时会终止子进程。

第十六批在 Ubuntu 的临时独立 MySQL 8.4.11、Java 21.0.12.1 上通过 8 项测试，覆盖 News 7 个、Messaging 20 个、Scheduler 176 个完整迁移，带固定旧数据的增量升级、News 实际 repository 的并发租约和保留清理，以及新旧引擎接续/校验失败。测试实例使用 `mysqld --no-defaults`、独立数据/临时/socket/pid 路径、回环监听、独立随机账号、非 root 身份和资源/存活上限，结束后删除。没有使用生产账号或访问业务数据库。

第十四批曾观察到 Flyway 9.22.3 的 MySQL 8.4 版本提示；第十六批升级后的新引擎运行日志中该提示为零。本次证明该具体版本组合及测试范围，不能替代真实业务备份副本、线上迁移/回退与跨服务联调。旧记录保留，最新证据位于源码仓库 `docs/verification/daily-news/2026-10-18-flyway-upgrade.md`。

## 激活顺序与保留验收

1. 完成完整发布文件摘要、相关 JAR/迁移和包索引检查；隔离 MySQL 的全量与固定旧数据升级已验证，真实业务数据库备份副本和回退兼容性仍需单独检查。
2. 补齐私有配置，验证不同角色凭据、同机回环或跨机 HTTPS、指定节点及 Python 依赖。暂不启用业务开关。
3. 更新 Scheduler、Executor、Messaging、News 和 Gateway 的版本；验证健康、实际 JAR 路径、节点注册及包摘要。不要用成功重启代替版本检查。
4. 审核来源权限并登记来源；在指定节点验证出口地区、MaxMind 数据库或账号、地区来源包。只记录脱敏结果。
5. 手动生成和阅读日报，验证 owner 隔离、取消与原键恢复；再启用采集/生成影子运行，消息投递保持关闭。GitHub 七日增长需真实时间采样，不能伪造回填。
6. 仅为明确授权的接收目标登记 Messaging 绑定，执行真实邮件/飞书验收，记录提供方接受、失败、未知和核对结果；未验收前不打开每日投递。
7. 按[保留与监控手册](daily-news-retention-operations.md)核对策略、单批清理和告警；持续影子运行前开启 News 清理，投递阶段再开启 Messaging 清理。QQ 官方主动推送限制仍未解决，不计入已通过渠道。

关闭开关不会撤回已经发出的消息，也不能恢复已清理正文。正常停用先请求取消未完成运行/投递并等待状态收敛，再停后台进程；保留原 owner、幂等键、投递身份和数据库。回退版本必须理解 News V7 / Messaging V20 的空正文与原键重放语义。


第十七批增加采集持久容量：News V8、默认额度配置、发布包 V8 门禁和 80% 用量预警。11 项隔离 MySQL 测试全部通过，覆盖完整 8 / 20 / 176 迁移及新增回填、并发额度、清理并发用例。升级前停止所有 News 写入者；旧程序不理解 V8 计费字段，不能在 V8 库上继续采集或清理。回退和容量范围见[保留与容量手册](daily-news-retention-operations.md)。此结论不替代实际业务备份副本、跨服务回退与现场渠道验收。


第十八批增加 News V9 控制身份预留。默认环境包含四类控制行额度，发布检查要求 V9 和对应仓储类。升级前停止全部旧 News 写入者；内容外键与新增账本不允许把旧程序作为可继续写入的直接回退。已接收运行的后续身份已由运行额度覆盖，容量耗尽不阻止其完成；旧地区/日报身份保守合并计入预览用量。详见[控制容量验证](../verification/daily-news/2026-10-18-control-capacity.md)及[运行手册](daily-news-retention-operations.md)。


### Messaging V21 发布门禁

第十九批增加 Messaging V21 与 `OutboundCapacityRepository` 制品检查，以及四项容量环境范围检查。完整发布必须带新 Messaging JAR；保留旧 JAR 的装配会被拒绝。迁移前停止旧 v2 写入者，保留 V21 兼容回退版本；不删除历史幂等记录来降低用量。新绑定、正文消息、未知键取消各自满额的行为，以及真实 MySQL 回填和并发证据，见第十九批验收记录。


### News V10 地区/来源登记门禁

V10 要求新 News JAR 内包含迁移与 `RegistryCapacityRepository`。来源、来源包、节点、地区任务、独立观测分别有有限全局额度；配置范围由发布检查器验证。已接纳地区任务已预留一个报告名额，达到上限仍可完成及重放。来源轮询游标只能引用已登记来源。

迁移前停止旧 News 写入者。V10 回填存量身份，不删除旧包、节点或报告；同一报告若被多个旧任务占用，或存在孤立来源轮询游标，会阻止迁移，必须调查而不能自动删除或修复历史摘要。新报告绑定任务后有唯一关联约束，旧未计费写法被拒绝；回退需保留 V10 兼容程序。完整真实数据库验证见第二十批记录。


### Messaging V22 发布门禁

第二十一批门禁新增 V22 迁移和 `OutboundDeadlineTimer` 类检查。完整装配必须包含新版 Messaging JAR；已声明正文期限的库不能回退到仅兼容 V21 的发送程序。迁移保留旧消息空期限和原策略，先升级所有 Messaging 写入者，再由 News 等客户端逐步使用期限字段和无正文取消接口。News 每来源期限传播仍未实现，本批不计为端到端业务验收。
