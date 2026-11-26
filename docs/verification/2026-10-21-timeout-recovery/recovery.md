# 昨晚十三条 X 排队超时任务的受控重跑

日期：2026-10-21，Asia/Shanghai。用户明确授权重跑昨晚因排队超时的十三条任务。

## 范围与防重复

- 选择 10 月 20 日 18:00 至 21 日 08:00 创建、task_name 为 download_x_post、状态 TIMED_OUT、且没有任何执行记录的任务；数量严格为 13。
- 校验每个原下载请求均无文件结果，并且恰好对应一个 FAILED 自动化运行和一个 FAILED 动作。
- 每条重跑通过 Download Ingestion 正常 API 创建新请求，使用固定恢复批次键及原请求 ID 构成幂等键；新 sourceType 为 RECOVERY。
- 原下载请求、原 Scheduler 超时记录及原失败通知全部保留；不直接修改 Scheduler 数据库，不将失败记录伪造为成功。

## 自动化跟踪与审计

使用 Automation 专属数据库账号进行参数化事务，锁定原失败运行，再将单个失败动作绑定新请求、恢复为 RUNNING，并清理已经耗尽的状态查询重试和租约。事务严格要求原运行和动作仍为 FAILED，且旧外部请求标识匹配，避免覆盖取消或并发更新。

同一事务写入固定 UUIDv5 幂等审计事件 AutomationRunRecoveryRecorded，记录原任务、旧新下载请求、运行、动作及原状态和错误码。该专用审计事件由恢复执行器同步处理，不作为机器人消息发送。后续实际完成由正常 Automation 对账与 CompletionOutboxRelay 发送新结果。

原任务 13 条均保持 TIMED_OUT。恢复运行只表示重新开始真实处理，不能将之当作下载已经完成。

## 执行记录

- 固定恢复批次：`x-dispatch-timeouts-20261021-v1`。
- 受保护映射日志：`/opt/yuyutian/mytools/secure-evidence/x-dispatch-timeouts-20261021-v1/mappings.json`，权限 0600；目录权限 0700。不含原始 URL、消息正文或凭据。
- 恢复脚本：`service/deploy/recover_x_dispatch_timeouts_20261021.py`。
- 只读核验：`service/deploy/verify_x_timeout_recovery_20261021.py`。
- 首次调用已创建第一个请求，但响应字段采用 snake_case，断言因此终止；修正解析后复用同一幂等键，取得原请求并继续，没有重复创建第一个下载。
- 全部 13 条恢复已提交并接回原自动化运行。初次核验为 2 RUNNING、11 QUEUED，已保存 2 个文件、2,894,998 字节，完成通知尚为 0。
- 13 个新任务的 dispatch_deadline_at 均为空。

凭据只在远端进程中读取使用，未输出或保存在仓库。Python 语法检查与 git diff --check 通过。

## 恢复回执幂等冲突修复

真实重跑后发现：原运行已发送失败回执，CompletionOutboxRelay 默认以 runId 形成幂等键；恢复同一运行后新成功结果复用旧键，与旧失败正文发生冲突，新完成事件变为 Conflict 死信。

修复仅针对有 `AutomationRunRecoveryRecorded` 审计、且完成事件创建于恢复审计之后的记录：使用稳定的本次 completion eventId 生成回执身份。普通通知继续使用原 runId；分页规则不变，同一事件重复投递仍使用同一键。

- 新增真实 H2 仓储测试：没有恢复审计时保持原身份；恢复前旧完成事件保持原身份；恢复后新事件匹配；不同运行不可借用恢复身份。
- 新增中继回归测试验证成功结果不复用旧失败身份；原有 38 项完成通知测试继续通过；合计 41 项相关测试通过。
- 本机默认 Java 26 与现有 Mockito/Byte Buddy 不兼容，第一次测试未能装载 mock。切换项目要求的 Java 21 后通过，没有安装或升级依赖。
- 仅替换当前 Automation JAR 的 CompletionOutboxRelay 和 AutomationRepository 两个类，保留其他代码与配置。发布目录：`/opt/yuyutian/mytools/releases/recovery-completion-identity-20261021-v1`。
- 通过现有 CompletionOutbox redrive API 重投本恢复批次的 4 条新死信；原始失败通知与其他历史死信未重发。
- 发布后核验：5 条任务 SUCCEEDED、2 RUNNING、6 QUEUED；已保存 10 个文件、11,236,102 字节；4 条新的完成消息 published，未出现新下载失败。
- 恢复脚本再次运行只读取原 13 条固定审计，未创建新请求或重新初始化已恢复运行，验证了幂等恢复。

## 持续核验

- 中途核验 7 条成功、7 条新完成消息已发送；随后推进到 9 条、10 条、11 条成功。
- 11 条成功时已保存 15 个文件、23,907,552 字节，11 条新完成消息 published，剩余 2 条正在执行，已无排队任务。
- 这批后续任务实际排队已超过旧五分钟期限仍能依次开始执行，进一步验证生产取消排队期限已生效；所有 13 个新任务的排队截止字段始终为空。
- 原 Scheduler 超时记录始终为 13 条，未被覆盖；新任务故障数始终为 0。

## 最终结果

- 最终独立只读核验：13 条新 Scheduler 任务和 13 条自动化运行全部 SUCCEEDED，失败数为 0。
- 已完成落库 16 个文件，共 28,266,894 字节；每个文件均为 COMPLETED，具有 storage_uri、asset_id 且大小大于 0。
- 恢复审计之后生成的 13 条新完成回执全部已发送；原始失败回执不计入此数。
- 原 13 条 TIMED_OUT 记录保留，13 个新任务的 dispatch_deadline_at 全部为空。
