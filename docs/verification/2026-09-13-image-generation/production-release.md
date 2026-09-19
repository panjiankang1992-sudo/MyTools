# 图片生成上线记录

2026-09-13，通过当前配置的 Ubuntu SSH 通道执行增量发布，与章节改编会话共享 `/opt/yuyutian/mytools/runtime/deployment.lock`。

## 当前能力

MyApp「工具 → 图片生成」已接入 Ubuntu Krea 2 Turbo 文生图，支持方形、竖版、横版，单次 1/2/4 张串行生成。远端和风格参考仍未通过验收，保持不可用；不会自动向远端发送请求。

新增图片服务和 Comfy 均已设置开机自启。Comfy 只监听回环地址，限制 MemoryMax=18G、MemorySwapMax=512M、CPUQuota=600%。图片和标签共享同一 GPU 锁；现有 8B 标签模型继续使用。

## 实际发布组成

为保留不可变产物，每次修复均采用新目录，未覆盖旧 JAR 或旧任务包，也未切换全局 `releases/current`。

| 组件 | 生效目录 |
|---|---|
| Scheduler、Gateway、普通 Executor | `releases/image-production-20260913-v1/apps` |
| 图片服务 | `releases/image-production-20260913-v3/apps` |
| 完整历史任务包及新增图片/标签包索引 | `releases/image-production-20260913-v4/task-packages`，共 131 个版本 |
| 普通 Executor Python SDK | `releases/image-production-20260913-v5/task-executor-sdk` |
| Comfy 和模型 | `runtime/krea2-evaluation-20260913` |
| 生成数据 | `runtime/image-generation` |
| 私密增量配置 | `config/image-generation.env`，root-only，不在仓库保存内容 |

上表路径均相对 `/opt/yuyutian/mytools`。具体文件摘要见 [生产结果](production-results.json)。部署新增独立图片 Schema 与用户；Scheduler 通过 Flyway 应用 V148–V152，通过受运维身份认证的 API 启用图片任务和集群，开关留有审计记录。

Reader v6 独立 JAR 路径及章节改编创建开关保留；Reader、改编 Executor 没有被本次重启。最后检查图片服务、Comfy、Scheduler、普通 Executor、Gateway、Reader、改编 Executor 全部 active。

## 线上验收

| 验证 | 结果 |
|---|---|
| 业务服务 → Scheduler → 真模型 → Asset Registry → 下载 | 成功，验收流程 56.5 秒，PNG 918,601 字节 |
| 幂等重复提交 | 返回同一任务，无重复出图 |
| 任务、图片、底稿跨所有者读取 | 拒绝 |
| 图片服务重启 | 历史任务及原 PNG 仍可读、字节一致 |
| 取消 | 验收任务进入 CANCELLED |
| 真实 App 竖版人物生成 | 成功，832×1216，服务端耗时约 50 秒 |
| 横版真实生成 | 成功，1216×832，51.17 秒 |
| 最终 App 自动状态更新 | 排队中 → 正在生成 → 已完成，无手动刷新 |
| 生产 SDK + 标签 1.4.0 + 生产 GPU 锁 | 成功，9.88 秒 |
| 独立 Comfy 启动内存故障注入 | 128 MiB cgroup 中确认 `Result=oom-kill`、退出 9；正常配置启动及随后真实出图成功 |

OOM 验证针对进程 RAM 限额，不代表已验证显存耗尽或长时间过载。最终一次资源检查显示 RAM 可用 15,343 MiB；整机 Swap 仍接近满额（8,190/8,191 MiB），本轮未清空 Swap。Comfy 当时实际交换约 184 KiB，进程组 MemoryPeak 约 7.25 GiB。长期运行仍需观察全机交换压力，不能用单次成功替代容量监控。

原始记录：[生产服务与任务结果](production-results.json)、[App 自动状态记录](app-live-states.json)、[App 作品页截图](production-app.jpeg)。

## 部署过程中修复的问题

1. Spring 仓库代理字段不能直接访问，改用代理方法；新增 CGLIB 代理回归测试。
2. 新任务包必须同时加入完整包索引，保留线上所有历史包摘要。
3. Python SDK 使用 `runpy` 时补齐已验证脚本目录的模块搜索路径，使图片及标签辅助模块可导入；新增真实子进程导入测试。
4. 认证 JSON 请求禁用系统 HTTP 缓存，任务卡片的渲染键包含状态与结果变化，预览使用正确的本地文件 URI。

最终图片服务 14 项、SDK 14 项、部署工具 74 项测试通过；Scheduler 图片开关权限及现有认证回归通过。App `assembleHap` 成功，认证 API 策略测试通过，`git diff --check` 通过。

## 回退边界

先通过图片部署管理 API 关闭新任务，等待已有执行结束，再按 `deploy_image_generation.py rollback` 回退共享服务的图片 drop-in。保留图片数据、任务审计和新标签包目录，不删除数据库，不回滚全局 current。图片服务的 v3 修复 drop-in、普通节点 SDK v5 属于实际发布组成，后续发布应一并带入，不能回到初始 v1 运行配置。

最终 HAP：`app/entry/build/default/outputs/default/entry-default-signed.hap`。已在 `127.0.0.1:15555` 验收虚拟机覆盖安装并保留登录数据；其他设备需安装新包才能显示入口。
