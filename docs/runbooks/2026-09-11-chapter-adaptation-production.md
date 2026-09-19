# 章节改编现网发布与验收

日期：2026-09-11。用户明确授权直接部署现网，当前只有一个真实书架用户。

## 发布结果

`releases/current` 当前为 `chapter-adaptation-production-20260911-v4`，在 v3 基础上修复真实网络书籍的格式兼容。Reader 和 Gateway 的改编创建开关已开启，正式第三方告知已发布；用户明确同意门禁仍保留。生产原有 44 条书架记录未修改，没有复制隔离测试账户的同意记录。

**现网真实书架章节后端验收已通过：初次改编、优化、重新改编均成功入库，原章未变，历史留存与幂等重放通过。** 用户的明确同意已通过正式 API 记录为修订 1。APP 改编页面联合操作尚未验收；本结论不等于设备页面端到端通过。没有为本次上线重复生成隔离合成样章。

## 本次修复与部署范围

- Reader、Scheduler 增加独立的原生 Tomcat 双向 TLS 监听，保留原有 HTTP 业务端口；客户端证书必须提供，工作负载身份仍由原有授权链验证。
- Scheduler 自身通过部署初始化器启用既有不可变改编模板，部署脚本不直接修改 Scheduler 数据库。本次没有新增迁移。
- Executor 使用已经完成隔离后端验收的包；专用生产节点与普通节点分离，模型凭据沿用用户最新更新的第 2 代，未再次轮换。
- 配置生产独立证书、角色目录、密钥环、告知及来源运行时。凭据仅在服务器私有文件中，不进入仓库或报告。
- 修复生产 CA 缺少严格校验所需的 Key Usage 扩展，保留现有私钥和叶证书身份，不关闭证书校验。
- 修复配置父目录因 umask 丢失组遍历权限、Scheduler 聚合健康等待在线 Executor 的启动依赖，以及本地验收请求误走代理的问题。
- 专用来源容器限制访问宿主、内网、元数据地址，保留必要的公网 HTTP/HTTPS 与 DNS；规则仅绑定专用网桥，并配置 systemd 持久化。原有阅读容器不变。

## v3 已通过的集中技术验收（历史阶段）

- Reader HTTP/mTLS、Scheduler HTTP/mTLS、Gateway、专用 Executor 共六个健康端点通过。
- Reader/Gateway 未认证访问返回 401；Scheduler 普通 HTTP 不能取工作负载密钥；正确 Reader 证书通过，错误 Executor 身份及缺少客户端证书均被拒绝。
- 普通节点和改编专用节点均 ONLINE。普通 HTTP 验收任务 `d42e9a20-5260-4713-8123-01aa5d55ffd3` 为 SUCCEEDED；脚本使用固定幂等键，重跑不会新建另一条任务。
- 来源容器到宿主与元数据连接被拒绝，公网及 DNS 可用；宿主监听器先做正向对照，避免把未监听误判成隔离成功。
- 2,765 个非目标发布文件逐一验证摘要不变。旧发布和全部既有审计保留，未删除用户内容。
- 本次新增/相关 Java 定向测试共 22 项，错误、失败、跳过均为 0，Java 21 打包通过。既有后端 94 项与 APP 40 项沿用前次证据，不计作本轮重新执行。
- v3 配置部署阶段新增模型调用 0，新增用户同意记录 0；授权后的真实模型验收另见末尾。

## 发布包与审计

| 服务 | SHA-256 |
|---|---|
| Reader（v4） | `83baa91eda012f1f341d26b7afea207578ac0f2407d976d74445500f26f76efe` |
| Scheduler | `bf12885fb8b9c9b55e2fa1d74c275eb8dd5e0e92a2cc6b60f8115a75efed6fba` |
| Executor | `11266021aef9f81838c0e05fc66a91c9c3ba738bd7b46574b9222283f5d4fc8a` |
| Gateway（未更换） | `4cf20b708d9daf18dc4408e3eee3756d869afd0d25cd2a99e83b20b70f837c3b` |

服务器私有审计目录：`/opt/yuyutian/mytools/runtime/adaptation-production-20260911/operator/`。

- `staged.json`、`activated.json`、`enabled.json`：候选部署、激活与入口开放记录。
- `production-preflight.json`：集中技术验收的脱敏结果。
- `production-final.json`：开放后再次核对六个健康端点、两个 ONLINE 节点、读写开关、运行时自启动和第 2 代凭据；状态为 `PRODUCTION_ENABLED_TECHNICAL_ACCEPTANCE_PASSED`，同意状态仍为 REQUIRED、修订号 0。
- `runtime-egress.json`、`certificate-strictness-fixed.json`：运行时网络与证书修复证据。
- `provider-registration.json`：正式 Provider 和告知版本摘要，不包含密钥。第三方留存、训练用途及删除机制尚未核实，告知没有宣称“不留存”。

部署与验收脚本分别为 `service/deploy/deploy_adaptation_production.py`、`service/deploy/verify_adaptation_production.py`，只适用于本次明确的生产发布目录，不是通用一键部署工具。

## 回退与剩余验收

保留前一发布 `chapter-adaptation-20260911-v2` 和本次私有配置备份。回退入口先检查改编任务无非终态记录，再由 Scheduler 自身关闭模板，恢复本次增添的配置与旧发布，并恢复普通节点；保留数据库新数据。**本次没有执行生产回退演练。**

用户已确认具有提交权限并接受正式告知，已完成真实书架章节的一次初次改编、优化、重新改编及留存验收。模型仍可能因结构或剧情约束检查失败，失败版本应留存，不能用一章通过推断任意长篇改写质量。

## 授权后的真实书架修复与验收

正式告知版本仍为 `production-20260911-v1`，同意时间 `2026-09-11T14:51:58.863877Z`，修订 1。审计 `production-user-authorization.json` 明确记录来自用户本轮聊天授权，而非冒称 APP 页面点击。

真实书架筛选发现 40 条旧占位记录、2 条测试记录、2 条真实网络小说。没有删除占位记录或把它们改成合成小说。选定《截胡女主，主角美母上门找我借钱》第 1 章 `1重生大反派`，原章 1,924 码点，SHA-256 为 `069fa1a6f13b199435c40355a03634c5ad0f0403ebbaf2815246401e0abcd2b2`；已阅读全文与下一章。验收仅增补开头环境，不扩写两性、骚扰或暴力内容，不提前下一章事件。

本轮确定并修复两个实际问题：

1. APP 允许书源小说 `unknown` 格式进入，后端准备入口却仅接受 `txt/epub`，实际返回 422/READER_031。后端现与 APP 的文本候选格式保持一致；未知格式仍须来自本人有效书源，并在取文阶段确认为文本。未伪造或覆盖书架格式。新增格式和非文本回归用例，合计 26 项 Java 定向测试通过、无失败跳过，Java 21 打包成功。
2. 专用运行时规则安装报 NEED_LOGIN。密钥文件与新容器一致，但 Reader 进程仍继承旧运行时地址/密钥环境变量。只移除 Reader 的这两个覆盖项后，目录真实准备成功；认证未关闭。上游 [认证参数实现](https://github.com/givenge/reader-rust/blob/master/src/api/auth.rs) 和 [服务身份解析](https://github.com/givenge/reader-rust/blob/master/src/service/user_service.rs) 支持服务密钥与显式命名空间，本轮没有更换运行时或通过 URL 传递密钥。另有 2 项部署配置回归测试通过。

v4 仅替换 Reader，增量传输 49,594 字节，远端校验完整 JAR；2,767 个其他发布文件摘要不变，部署脚本不修改业务行。Scheduler、Executor、Gateway 未重新构建或重启。旧 v3 保留，Reader 单包配置备份及修复记录在 `reader-source-format-hotfix.json`、`runtime-environment-fixed.json`；回退 v4 须核对 Reader 单包路径并保留已修复的运行时环境隔离，不能直接对 v4 运行仅识别 v2/v3 的旧回退脚本。

目录当前 READY，绑定与目录修订均为 1，已取得 5 个可信文本章节。唯一正式验收 unit 为 `mytools-adapt-real-shelf-20260911`，审计在 `operator/real-shelf-acceptance/`，初次、优化、重改均使用固定独立幂等键；某一步失败即停止后续派生，不额外生成碰运气。

### 真实书架最终结论：REAL_SHELF_BACKEND_PASSED

| 操作 | 留存版本 ID | 正文码点 | 相对原章新增码点 |
|---|---|---:|---:|
| 初次改编 / v1 | `99db5169-732a-4f82-8a47-221b5f7320e8` | 2,002 | 78 |
| 优化 / v2 | `90d1184b-e3ac-4a8f-827d-f98922cc72ea` | 2,004 | 80 |
| 重新改编 / v3 | `ba677092-0393-444e-8b26-1a86f75a8ddf` | 1,996 | 72 |

三版均 COMPLETED，对应三条 Scheduler 任务均 SUCCEEDED；PLAN、GENERATE、CRITIC 共 9 次 Provider 调用，全部 HTTP 200、各发送一次、凭据代次均为 2，没有新增修复调用或手工重试。三份结果摘要、对比重建、谱系与成功历史通过；三类请求幂等重放返回原版本，调用数仍为 9。

人工复核原章、下一章以及三份实际采用增补：所有差异均为插入，删除这些插入能逐字恢复原章；增补仅在开头原文位置 0 或 186，不改变角色、对白、事件、已知信息或结尾，没有提前下一章接车、见面及比武。重新读取真实书源确认原章摘要不变，书架元数据与版本不变。

语义复核为 `PASSED_SCOPED_WITH_STYLE_NOTES`：阳光、清晨或正午等细节属于新增环境推断，本次未发现与相邻章冲突，但并非全书时间线质量基准；“优化”确实改动了新增措辞，不等于文学质量必然提高。没有为了得到更满意的文字再生成。

最终复核六个健康端点均 UP，非终态改编为 0，批次退出码 0。完整脱敏结果见 `operator/production-real-shelf-final.json` 和 `operator/real-shelf-acceptance/report.json`，摘要绑定的增补复核在同目录 `quality-review.json`。本轮未重新构建 APP、未执行 APP 页面点击、未重跑生产 Reader 重启恢复测试；既有隔离重启证据仍是前一阶段的证据。
