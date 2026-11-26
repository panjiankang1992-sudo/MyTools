# 下载完成回执修正

## 核查

用户反馈 APP 与 QQbot 都显示失败，但文件实际已下载。经授权只读核查 Download 数据库，10 月 1 日多笔 FAILED 请求拥有已归档结果、非零大小、TAGGED 标签终态。原来两端均优先按请求总状态生成“下载处理失败”标题，未区分文件已保存与真正没有下载结果。

本轮未确认父任务最终失败的具体步骤：下载数据库用户无 Scheduler schema 读取权限；对应日期 Executor journal 已无可用记录。没有修改历史请求状态、重放下载或重发 QQ 消息。

## 修改

- APP：FAILED 且结果中存在非空归档地址，显示已有文件下载并归档，但部分处理未完成；无成功归档证据时保留失败提示。文件大小、标签与归档地址仍逐项展示，任务状态不改写。
- QQbot：使用真实结果标签终态识别已保存文件，失败占位 DOWNLOAD_FAILED 不视为已保存；标题说明已有文件并保留部分处理未完成的提示。
- 已发送的 QQ 消息不会自动更新。APP 历史消息在更新后按新规则渲染。

## 验证与发布

- APP 下载回复策略测试通过：失败带结果、部分结果、无结果、空归档地址及其他生命周期状态。
- HarmonyOS 签名构建成功，无 WARN / ERROR。
- CompletionOutboxRelayTest：38 项，0 失败，0 错误。首次沙箱内 Mockito attach 失败，正常授权运行后通过。
- QQbot：只替换线上 CompletionOutboxRelay.class；部署前校验原类 SHA-256、比较类接口兼容性。发布 download-reply-20261003-v1，旧包保留，启动和健康检查通过。
- APP：覆盖安装到 BRA-AL00 真机成功。随后设备断开，未完成新页面真机目视验收。
- 没有生成下载测试文件，也未向 QQ 用户发送测试消息。

## 后续根因核查

经用户授权，使用下载服务现有业务身份只读查询 Scheduler。父任务 db83421d-1f2d-4e55-8b95-92513da56f97 在 orchestrate_message_urls 等待子任务时失败；同批另两笔父任务同样报 one or more child tasks failed。

关联子任务 dee79e75-b55a-4769-abd7-e1e66c1baa9b 的 download_asset、publish_asset、register_asset、register_media_item、record_result、generate_thumbnail、generate_tags 全部 SUCCEEDED；record_tags 于 2026-10-01T02:33:57Z 失败，错误码 TASK_INPUT_INVALID，错误 media tag projection context is incomplete。

失败步骤上下文核验：assetId 存在、ownerId 为正、media registered 为 true。线上脚本的唯一剩余失败条件是缺少 MEDIA_LIBRARY_INTERNAL_TOKEN。实际执行器 JAR 的 download_record_tags 环境映射仅含 DOWNLOAD_INGESTION_URL 和 DOWNLOAD_INTERNAL_TOKEN，缺少媒体库地址和鉴权令牌映射。

脚本先把标签保存到 Download，再尝试同步 Media；所以 Download 记录显示 TAGGED 且文件已保存，但 Media 标签同步报错导致子任务与父任务 FAILED。前述 WAL 警告不是本次已证实的直接原因。此轮仅核查根因，尚未修复执行器环境映射或补同步历史标签。


## 根因修复与历史补同步

- 已为 download_record_tags 单独补齐 MEDIA_LIBRARY_URL 和 MEDIA_LIBRARY_INTERNAL_TOKEN 环境映射。令牌继续由服务端现有环境提供，不写入代码或输出。
- 发布 download-tag-env-20261003-v1，仅替换执行器 JAR 中 application.yml 的对应脚本环境块，旧 JAR 保留。重启前未检测到执行器子进程；重启后健康检查 UP。
- 对前述失败记录，按照新线上 JAR 的环境映射重新执行原 record_tags 脚本，返回码 0、结果文件 TAGGED。只重试该幂等步骤，不重下文件，不创建新任务；临时结果目录已清理。
- 扫描 1246 条已生成标签的下载结果，109 条缺少完整媒体标签。其中 1 条由上述真实步骤复验补齐，其余 108 条通过媒体库接口完成幂等补同步，逐条 GET 校验新增标签和原标签均保留，0 失败。
- 737 条没有匹配到有效 DOWNLOAD 媒体条目的记录跳过；没有重建媒体、覆盖文件或修改任务历史状态。
- 4 项脚本测试通过，包含鉴权环境映射回归测试。
- 最终复扫：可匹配的 509 条下载结果标签全部齐全，待补同步 0；服务器临时部署、补同步脚本及临时汇总已删除。
