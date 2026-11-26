# APP X 用户下载恢复

- 检查北京时间 2026-10-28 至 2026-11-04 当前记录。两条原始 APP_TOOL 请求真实失败，四条已成功请求状态未同步。
- 失败原因：APP 的 UUID 批次标识被 X 用户脚本误用为消息身份，调用 processed-links/claims 时不存在 automation_run，返回 HTTP 500；两次脚本尝试均失败。
- 修复：DownloadRequestService 为 APP_TOOL 的 MESSAGE_URL_BATCH、X_USER 调度参数生成 app:<requestId> 身份。现有脚本只为 UUID 消息登记去重；机器人消息身份保持原值，原请求参数与幂等比对保持不变。
- 27 项服务测试通过，覆盖 APP 与 MESSAGE 身份、参数保存和幂等提交。
- 部署基于当前实际安装的 Python 模块复制新目录后仅插入本修复，未覆盖本地其他未发布变化。目录 app-download-identity-20261104-v1，systemd 专用 PYTHONPATH drop-in 可回退。
- 首次部署复制的目录为 root 私有组，服务回落到旧模块，两条第一次恢复失败。已修正 root:mytools 权限并重启，新任务父子层均验证为 app: 身份；第一次恢复记录保留。
- 四条成功任务通过现有 GET API 完成状态对账，无重复下载。另一个原 PLANNING 请求对账为 FAILED。
- 两条原失败请求通过 Download API 以固定 v2 恢复幂等键重跑；不直接写 Scheduler 数据库。映射保存在 secure-evidence/app-x-identity-recovery-20261104-v2/mappings.json，0600 权限；不含 URL 与凭据。
- 当前两条新请求：eba35d3d-715e-4fad-b51b-7fa29da49b29、dc9ee075-ca2f-40e6-8974-19ccf0b1f728；待核验终态和文件结果。

## 后续核验

- 完整下载服务测试通过：46 项。第一次全量测试缺少 src 导入路径，修正 PYTHONPATH 后全部通过。
- v2 第一条重跑不再出现消息登记 HTTP 500，但 gallery-dl 整页读取在 180 秒内未完成，两次尝试后失败；第二条目前仍在执行。尚未确认两条恢复成功。
- 同一用户只读取 1 条媒体的受控探测成功（退出码 0，4731 字节元数据）；100 条整页读取探测 50 秒未完成。认证与基本连接可用，但整页枚举耗时问题尚未修复，不能据此判断所有帖子都可读取。

## 媒体列表超时修复

- 安装版本 gallery-dl 的用户根 URL 默认派发到 timeline，而不是媒体专用接口。实际对照：同一用户单媒体读取成功，根时间线整页读取超过 180 秒；媒体页 100 媒体上限读取在 50 秒内完成，返回 26 个媒体、7 个帖子。
- 新不可变包 download_resolve_x_user/1.1.0 将已校验的用户 URL 统一到 /media。分页末页判断使用原始媒体条目数量，避免同帖多图去重后被误判为末页。
- 新版 5 项测试通过，覆盖原有帖子去重、二十条批次、消息登记，以及多图满页继续分页、APP 命名空间不调用消息登记。
- V178 迁移只升级现有 X 用户脚本步骤版本。发布目录 x-user-media-timeline-20261104-v1；上线前无 RUNNING 执行，Scheduler 和 Executor 重启后均 active，健康检查通过。保留旧包和旧 systemd 启动命令用于回退。
- v3 恢复幂等键与映射目录为 app-x-media-recovery-20261104-v3。两条原失败请求均从原始参数经正常 Download API 创建新请求，继承 APP_TOOL 来源，所有权由服务传递，不直接写 Scheduler 数据库。
- 第一条新请求 1a8130ad-dbb3-4983-81ee-bf6631635e6a 已真实进入 7 个帖子子任务，初步已落库 4 个文件，第二条 bef187d0-6a53-469c-ac16-72e03a973a1f 待顺序执行。继续等待最终结果。

## 第一条恢复成功与批次预算修正

- 第一条 v3 新请求 Scheduler 终态 SUCCEEDED；全部 7 个帖子任务和 26 个 HTTP 媒体任务 SUCCEEDED。26 个 COMPLETED 文件共 4,853,041 字节。经 Download GET 对账后请求也为 SUCCEEDED。
- 媒体后处理慢的主要原因是 GPU 标签服务繁忙，部分步骤发生 GPU_BUSY 自动重试；已成功任务不能与未完成的标签步骤混为一谈。
- 第二条用户媒体页对照读取成功，76 个媒体、36 个帖子。为避免大型批次被旧脚本内固定 1800 秒的共享等待截止时间提前取消，新增不可变包 1.1.1，批次使用用户任务已有的 86400 秒执行预算，任务自身执行超时仍保留。
- 1.1.1 的 5 项测试通过，二十条批次测试同时验证 86400 秒预算。V179 迁移升级现有步骤版本。
- 第二条尚未开始的 v3 恢复请求经正常 API 取消（无下载文件）；待第一条成功且无 RUNNING 执行后，再发布 x-user-media-wait-20261104-v1 并重启 Scheduler/Executor，两服务均 active 且健康检查通过。
- v4 仅创建第二条的新恢复请求 2c94def8-89f1-427c-bc89-7100739f93b8，任务 768c8364-6542-4186-bcf4-917bc62aaa48；第一条复用已成功的 v3 结果，不再下载。受保护 v4 映射同时保留两条最终恢复对应关系。

## 真实恢复中的资源故障与租约恢复

- 第二条处理到 26 个文件后，服务器内存仅约 300 MiB 可用、8 GiB swap 用尽；Scheduler Hikari 连接获取和 Executor 心跳超时，5 个执行租约失效并被 Scheduler 重新排队。
- 当时标签模型 huihui_ai/qwen3-vl-abliterated:8b 的 size_vram=0，llama-server 常驻内存约 16 GiB、CPU 约 540%；显卡仅占用约 303 MiB。确认模型实际落在 CPU 运行，未把此故障误报为 X 网络故障。
- 重启 Ollama 释放模型后，CUDA 重新发现 RTX 5060 Ti；正常 API 预加载同一模型后 size_vram=5,793,780,858，系统可用内存约 16 GiB，GPU 推理明显加速。添加 60-mytools-gpu-ready.conf：下次服务启动前先执行 nvidia-smi -L，并排在 nvidia-persistenced.service 之后；没有切换模型或降低标签要求。
- Executor 因 EXECUTION_LEASE_LOST 报告进入人工诊断，暂停领取新任务。停机后逐一只读确认相关 execution 均为 TIMED_OUT 且 lease_lost_at 非空，再以 mytools 系统账号备份完整本地 SQLite Journal，并把 6 条旧 PENDING/DIAGNOSTIC 报告、5 条失效 CLAIMED execution 从活动 Journal 隔离。未伪造 Scheduler 成功，未修改 Scheduler 数据。
- 原始完整数据库与无令牌的审计列表保存在 tasks-report-journal/manual-stale-lease-recovery-20261104-v1/，目录 0700、文件 0600；活动数据库写入独立恢复审计行。原始历史成功上报保留。恢复脚本 recover_stale_download_journal_20261104.py。
- 重启 Executor 后沿用原请求和已存在子任务恢复，没有新建另一份用户下载；第二条随即推进到 13 条帖子成功、40 个文件落库。
- v4 恢复接口再次调用返回相同请求及任务标识，未生成重复任务。

## 最终核验

- 两条原始失败请求均恢复完成：第一条新请求 SUCCEEDED，7 条帖子、26 个媒体文件；第二条新请求 SUCCEEDED，36 条帖子、76 个媒体文件。全部对应帖子任务与 HTTP 媒体任务均 SUCCEEDED。
- 最后经 Download GET API 完成请求状态对账，两个请求均返回 SUCCEEDED。
- 共 102 个文件、26,664,684 字节。逐文件数据库条件核验全部通过：COMPLETED、size_bytes > 0、storage_uri 和 asset_id 非空、content_sha256 长度 64。不是以任务受理或部分落库当作最终成功。
- 两个恢复父任务 dispatch_deadline_at 均为空。先前第一条已成功结果未在资源故障恢复中重新创建，第二条沿原请求恢复；原失败及中间尝试保留以备审计。
- Scheduler、Executor 健康接口均 HTTP 200 / UP。模型经实际加载确认使用 GPU，第二条随后迅速完成。
- 本次代码验证：完整下载服务 46 项测试通过；最终用户脚本 1.1.1 的 5 项测试通过。Python 发布/恢复/最终核验脚本语法检查、git diff --check 通过。
- 另已通过正常查询对账修复 4 条原本实际成功但显示未完成的请求。

## APP 可见结果补充修复

- 后续全流程分析发现身份发布包错误地以基础 venv 模块为底，而原线上 APP 功能来自 systemd PYTHONPATH 指向的 download-chat 完整发布包；原覆盖丢失 APP 历史和详情路由。该回归由本次发布引入，已明确纠正。
- 新发布 app-download-identity-20261104-v2 从实际原 APP 完整包复制，只增加 APP 批次身份修复，并保留原 PYTHONPATH 作为后继搜索路径。APP 历史、详情和原下载 API 均保留。
- 经最小权限 Download 账号只读核对两条新旧请求的同所有者、同 APP_TOOL 来源、同原始参数及新请求成功状态后，以 mytools 系统账号对 APP 本地 SQLite 的两个原 entry 执行参数化条件绑定更新。
- uploads.sqlite3 同目录 manual-ui-recovery-20261104-v1/before.db 保留原始数据库，目录 0700；recovery_audit 记录幂等键、entry、新旧请求标识和时间。没有覆盖原下载失败终态。脚本重复执行变更数为 0。
- 实际 APP history 返回 30 条记录约 0.66 秒，两条原 entry 显示 SUCCEEDED；分别查询 detail 确认 26 和 76 个文件。修复脚本 deploy_app_download_identity_routes_20261104.py、rebind_recovered_app_history_20261104.py；核验 verify_app_history_recovery_20261104.py。
