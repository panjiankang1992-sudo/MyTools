# 全文改编与模板现网部署记录

## 当前结论

Reader v6 修复已部署，创建入口已恢复。此前 V4 的失败记录保留；以下旧发布与诊断段落为历史记录，以文末修复后验收为准。

- 当前发布：`chapter-adaptation-production-20260913-v5`。
- Reader 已迁移 V39/V40，六个默认模板初始化成功；通过独立管理 API 发布 `enriched-novel` v1（加料小说），共七个模板。
- 模板技能先读取版本，再发布并回读摘要。未继续电子书语料分析。
- 新请求能力曾核对为 `NOT_REQUIRED`、revision 0、无同意记录，不伪造授权；因为实际生成失败，后续暂关创建。
- 只替换 Reader、Gateway 和独立命名的 `adaptation-executor-v2.jar`。普通 Executor 未重启或替换，2,773 个非目标发布文件摘要不变。
- 数据库备份在服务器私有 `operator/v2-upgrade-20260913/reader-before-v39-v40.sql`，未下载或输出。

## 本轮验证

Java 21 改编定向测试：Reader 301、Gateway 21、Executor 57，失败/错误/跳过均为 0。测试有既有 Mockito 动态 agent 提示，不是 Java 编译警告。

虚拟机 `127.0.0.1:15555`：章节右侧图标、目录、七模板下拉、中文意图输入、一次首次提交、失败版本留存及展示均实测。修复最新版本行的值传递导致标签陈旧，以及默认键盘 OFFSET 遮挡提交按钮的问题；改为引用绑定和局部 RESIZE，退出后恢复原键盘模式。

最终 HAP SHA-256：`fd99958ea2ce4e51c2ac052889c1e3006e0ac9cbf9172738909aa67c15cfe6d7`。安装记录：`app/build/acceptance/device-acceptance-20260913T080641Z.json`。未安装真机。

## 失败证据与诊断边界

APP 仅提交一次，生成 V4：`db04d1c2-ea51-42ef-92fc-19892c256319`，INITIAL / DIRECT / novel-adaptation-v2 / story-constraints-v2，最终 FAILED / READER_049。

对应任务 `9a1a32b5-dbad-4795-a3b7-e2219d67d312`、执行 `d4f225a4-e9c8-48eb-b107-72161fdd1114`。四个上下文片段已经 SEALED；所有片段码点长度、内容摘要、manifest、模板提示词摘要和合成意图摘要均一致。模型调用记录为空，尚未调用 Provider；不是模型速度问题。

在服务器内存中用真实 Executor 类进行离线检查，`NovelAdaptationWorkflow.context` 与 `plan(...).rewriteProtocol()` 通过，`fullRewrite=true`。这不代表 Reader 的再次领取/封存重读或完整工作流通过；确切抛错位置仍待定位。未执行优化、重改或盲目创建下一版。

继续扩展只读诊断程序时，环境审批拒绝上传代码；也拒绝导出完整上下文的方案。没有绕过限制，没有下载正文或任何密钥。待用户明确批准向已配置 `home-ubuntu` 上传并运行只读诊断后继续。诊断不得调用模型或修改数据库，只输出阶段与代码位置。

## 部署修正与恢复

### 只读诊断获准后的定位

用户明确批准后，诊断程序已上传并在服务器内存运行。实际 Reader 封存重读、Reader 序列化到 Executor 的上下文解析、v2 Prompt 构造均通过，未导出正文、调用模型或修改现网数据库。

已确定直接原因：`AdaptationStoryRepository.workflow` 的协议判断只允许 `story-constraints-v1`，在已封存的 v2 任务首次读取工作流时抛出 READER_049。`current` 和 `constraints` 也残留相同判断；此外校验记录写入及重放验证硬编码了 v1，需要一起修正，否则后续会再次失败。

本地修复已完成：明确支持 v1/v2，未知版本仍拒绝；冻结规则版本必须与任务一致；校验记录使用任务实际版本，重放仍核对版本、内容及摘要。新增整条 Reader v2 工作流（读取、计划、成文、评审、采用、幂等重放）和未知/混用版本拒绝测试。测试使用本地 H2 和合成文本，没有模型调用。本次修复尚未部署，现网创建开关保持关闭。

复制发布目录时一度未继承属主/组，导致服务读取受阻。已按旧发布逐项恢复 3,610 项元数据，并修正部署脚本；无需重建数据库或更新密钥。后续启动、迁移、模板发布和健康检查成功。

安全恢复：先完成确切原因修复，使用同一发布审计和新不可变修复包；保留失败 V4，不篡改为成功。核对无在途任务，部署并离线回归后，使用受控开关恢复创建，再做一次明确的修复后验收。不得直接把旧 Executor 接到 v2 / DIRECT 在途任务，也不得降级删表。

## Reader v6 修复后部署与验收

### 图片功能部署后的合并包回归

2026-09-13 再次通过 SSH 核对 Reader v6 包摘要、Reader/Gateway/改编 Executor active、创建开关 true 和 NOT_REQUIRED。V5/V6/V7 结果及摘要仍一致，原文未变，无在途改编。未重复部署相同后端包或重新生成。

与图片会话完成虚拟机交接后，在当前已安装包连续通过：章节右侧图标 → 历史 → V7 详情 → V7 正文 → 新建表单；七个真实模板可选，加料小说选择成功；意图为空时提交禁用。截图 `catalog.jpeg`、`list.jpeg`、`detail.jpeg`、`reading.jpeg`、`form.jpeg` 已更新为本次回归，另存 `combined-package-form.jpeg`。本轮未调用模型。当前本地产物 SHA-256 为 `9a9d954a30ffef41984719ec521a177f8fd05abd4a7bbdf1820bc24fd09091be`，未重新安装；后续图片会话正在开发的新 UI 包不在本次验收范围。

本地改编策略、会话/持久化日志、认证 API 策略及集成测试通过。验收结束已释放 15555 给图片 UI 会话；未改动其代码或重启任何生产服务。

- 与另一图片生成部署会话约定共享 `/opt/yuyutian/mytools/runtime/deployment.lock`；切换期间独占持锁，不切换全局 `releases/current`。
- 仅修改 Reader adaptation drop-in 的 JAR 路径及既有 Reader/Gateway 创建开关；本轮仅重启 Reader/Gateway，未重启 Scheduler、普通 Executor 或独立改编 Executor。另一会话等待本轮模型终态后再切换。
- 不可变 Reader 包：`reader/artifacts/reader-v6-b53d77875674.jar`，SHA-256 `b53d77875674ab8ca8f82720638c96bcd16f41e8fbe4fd0bb63df74139e1a24c`。审计和原配置备份在现网私有 `operator/reader-v6-20260913/`。
- 新旧包 migration 文件逐项相同，未执行结构变更。健康检查通过，创建开关开启，能力接口返回 `NOT_REQUIRED`。
- Java 21 Reader 改编测试 303 项通过，并成功打包；本轮沿用已安装的最终 HAP，不操作真机。
- 虚拟机 V5 首次改编、V6 优化改编、V7 重新改编全部完成，每版 PLAN/GENERATE/CRITIC 各一次 HTTP 200，总计九次调用，无重试。
- 当前已打开实际 V5 正文、V5/V6 详情并核对来源显示；截图为 `v6-completed-detail.jpeg`、`v6-reading.jpeg`、`v6-optimized-detail.jpeg`，位于 `app/build/acceptance/chapter-adaptation-option2/`。
- 最终历史列表同时显示 V7/V6/V5，失败 V4 仍在；已打开 V7 详情和正文。新增截图 `v6-final-history.jpeg`、`v6-regenerated-detail.jpeg`、`v6-regenerated-reading.jpeg`。
- API 回读三份结果并重新计算 SHA-256 均匹配存储值：V5 `f55553cd-a985-47c2-a252-0a6b1333b590`（2290 码点）；V6 `5afde81e-8aa0-4e2c-bec7-c0845ffcb9e9`（2342 码点）；V7 `23ab3388-c9c1-4bfa-ae34-61766b584bb4`（1919 码点）。V6 parent/root 为 V5；V7 root 为 V5、trigger 为 V6、parent 为空，符合从原文重新生成语义。
- 重新读取实际原章节并重算摘要，仍为 `069fa1a6f13b199435c40355a03634c5ad0f0403ebbaf2815246401e0abcd2b2`；全局在途改编为 0。完成后已通知另一会话接手虚拟机及服务切换窗口。
- 结论：本章首次生成、优化、重改、留存、详情和阅读的现网功能验收通过。不是所有章节生成质量、必然扩长或剧情零偏差的保证；本轮没有新增像素级设计评审。
