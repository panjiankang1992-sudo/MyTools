# 视频生成（VACE / LTX）进展与状态

> 更新：2026-09-28。本文件是这条线的**单一入口**：当前状态、版本沿革、验收结论、修过的坑、
> 待办与运维要点。细节与原始证据在 `docs/verification/2026-09-14-video-generation/`，
> 验收标准在 `docs/research/2026-09-14-video-generation/acceptance.md`。

## 2026-09-28 Wan 2.2 候选切换

- 后续补齐 6 条图生、完成 3 条真实文生，共 9/9 生成成功、873 帧联系图审查，全部 MP4 整段解码通过。文生图确认没有 LoadImage/首帧输入。挥手和树叶摆动可辨，蒸汽样例容器和背景不符；风景有局部形变，尚未开放生产。
- 更正上一轮基于缩略图的过强结论：商品原尺寸帧有明显放大，不能仅由横向位移断定没有推进；风景不能全部归因于整体横移。原始视频与更正记录均保留。15 项相关测试通过，新增三个窗口恢复，服务 active、队列为空。

- 改用 Wan 2.2 TI2V 5B 对照，不再仅调整 LTX 2B。主模型及专用 VAE 下载完成，官方 SHA-256 全部匹配；复用既有 UMT5 和原生节点，无运行环境升级。
- 首条人像 97 帧 / 24 fps 真实生成成功，约 195 秒。AI 全帧复核显示持续闭眼低头明显改善，但额外眨眼/轻微头部转动仍需扩大验证，未开放 App 模式。
- 候选结构测试 11 项通过；合计 4/4 实际生成成功，耗时 120.71–195.02 秒，388 帧 AI 联系图检查。两个人像较 LTX 改善，但商品/风景镜头仍偏移。两个窗口恢复，正式模式未开放。[候选报告](../2026-09-28-wan22-candidate/README.md)。

## 2026-09-27 继续验收与图片限制修复

- 再次继续：发现并修正两阶段候选第一阶段漏掉末次去噪的问题，确认当前 VAE 默认解码条件已一致；补齐末步与动作优先提示词分别完成 3 条对照，新增 582 帧检查仍未通过。LTX 测试 8 项通过，未更改正式 App 或开放模式。[后续修正与样例](../2026-09-27-video-controls/README.md)。

- 后续完成 CFG、尾帧约束、跨种子与两阶段空间放大共 12 条真实候选推理，检查 1164 帧；仍有动作及镜头不遵循，未放开生产。7 项 LTX 图测试通过，5 个 GPU 窗口均恢复，服务 active 且队列清空。[对照报告](../2026-09-27-video-controls/README.md)。

- 图片底稿原图 1 MB 限制已改为自动适配；同时修复认证客户端 2 MB JSON 限制。三类图片原生测试通过，正式 App 已安装到连接真机和模拟器。[图片报告](../2026-09-27-image-reference/README.md)。
- 视频新增 97 帧 / 24 fps、三类独立素材实际推理，3/3 完成；全部 291 帧复核仍有镜头偏移及人像闭眼持续问题，不开放生产模式。GPU 窗口已恢复。[视频报告](../2026-09-27-video-candidate/README.md)。

## App 隔离补验（2026-09-21，本轮）

- 真实模拟器 App 向回环 HTTP 夹具提交六种视频模式，素材角色与固定输出规格断言通过；系统相册/文件选择、取消、保存状态、账号切换、服务恢复已补测。**不是正式真实生成或画质全通过。**
- 修复不确定 VIDEO_010 响应导致重试重复任务、跨账号图片缓存残留、复用参数未同步入口分组、恢复后旧错误提示残留；前两项有修复前后实际 UI/HTTP 证据。
- 正式 HAP 构建成功并恢复安装到模拟器，真实生产模式仍关闭；夹具服务/端口已清理。真机、真实新 LTX 全链路、遮罩语义和若干异常/播放边界仍未完成。
- [本轮报告与证据](../2026-09-21-video-app-isolated/README.md)。

## 2026-09-21 接续结论

- 用户授权 AI 自主验收，无需人工评分。本次复核以 `ai:codex`、`notHumanReview=true` 记录，保留原有评分来源。
- **首帧画质不通过，所有视频生成模式现已关闭**：逐一查看正式 6 条、稳定性 10 条的全部 784 帧联系图。正式组 3/6 有限通过，另 3 条存在严重区域变色；均值颜色指标会掩盖相反区域颜色变化，不能据此开放模式。见 [AI 复核报告](ai-review-20260921/README.md)。
- **连续 10 任务服务稳定性通过**：10/10 成功，491–499 秒/条，显存峰值 14519 MiB，最低可用内存约 12797 MiB；窗口 `restored=true`，恢复进入前容量及图片 Comfy。见 [稳定性证据](stability-20260920/summary.json)。其中两条仍有严重区域变色，服务成功不等于画质通过。
- **B06 长等待修复**：原图片锁等待在 120 秒失败，标签步骤还有 180 秒上限。新增调度器节点级视频/GPU 准入，视频运行时图片及内嵌标签任务保持排队，CPU 任务可继续；GPU 任务排队期限至少 7200 秒，非 GPU 保持原策略。12 项针对性测试通过，覆盖并发领取、超过旧期限继续排队与原超时/排空回归。测试使用 H2 的 V140 基线，因为既有 V141 的 MySQL `JSON_SET` 不兼容 H2；并非全量迁移测试。生产真实长视频与图片混合负载仍待新画质候选验收时补测。
- 调度器已单独部署 `video-gpu-admission-20260921-v1`，健康检查 UP；部署前任务为 0，保留全部启动配置，仅替换 JAR。生产成员摘要差异仅包含本次两个修改类及一个新增类（含内部类）；无任务包/迁移变更。见 [部署记录](ai-review-20260921/scheduler-deployment.json)。
- 验收窗口支持持久恢复、独占锁、竞争检查及退出恢复；AI/机器评分来源有保护。10 项 Python 测试通过。
- 下载恢复任务已通过 V161/V162 恢复下载容量并更新标签包，见 [QQ 下载恢复验证](../2026-09-21-qq-download-recovery.md)。当前 Scheduler 为 `qq-tag-reuse-20260921-v4`，已现场确认仍含 `GpuTaskRepository`；本线不覆盖该发布。

## LTX 候选接续实验

- 已下载固定版本 LTX-Video 2B 0.9.8 distilled 与 T5 XXL scaled FP8，实际校验 SHA-256；使用现有原生节点，无依赖升级。
- 两轮共 12 条隔离实验已完成：r2 画质 2/6 有限通过，r3 原生比例加具体提示词后 6/6 开发样例有限通过；产品模式保持关闭。方法、配置及边界见 [候选报告](ltx-candidate-20260921/README.md)。
- 首轮清理缓存的空响应解析失败，未开始 GPU 推理；窗口已恢复。修复后 r2/r3 均完成且恢复窗口，新增三项空响应/中断范围测试通过。
- r3 为 640×640 / 49 帧 / 16 fps，6.21–12.41 秒/条，采样显存峰值 13783 MiB。两轮全部 588 帧已做 AI 联系图复核；开发集结果不等于独立素材或生产验收通过。

## LTX 正式接入（2026-09-21 晚）

- 已发布 `video-ltx-20260921-v1`：任务包 `video_generate/1.1.0`、迁移 V163、服务多模型能力目录。执行器已上报新包，全部产品模式仍关闭。
- Scheduler 成员摘要与 QQ 标签修复发布相比仅新增 V163；视频服务仅改能力目录、作业快照与配置，保留原执行器和全部已钉包。
- LTX 使用原生比例推理，完成后补齐 640×640 / 49 帧 / 16 fps；同一 GPU 锁内先用本机视觉模型整理中文提示词，卸载后再生成，并保存实际英文提示词。
- 本地验证：任务包 54 项、Java 35 项通过，App `assembleHap` 成功；验收辅助脚本补 3 项鉴权与 202 响应回归测试通过。
- 部署修复：systemd 片段必须按实际已有文件名追加；执行器包能力键为 `video_generate:1.1.0`。启动检查失败的尝试已回滚，最终新版本与 V163 均已确认生效。
- 真实探针发现执行器缺少 Pillow（已安装固定 12.1.0），以及本机视觉模型把完整 JSON 放在 thinking 字段。后者以不可变包 `1.1.1`、V164 和发布 `video-ltx-20260921-v2` 修复，56 项包测试通过。逐成员验证新 JAR 与本地构建一致，重跑连续十任务 10/10 成功，18.06–18.08 秒/条；独立六样例逐帧 AI 复核仅 2/6 有限通过，动作遵循未达标，所有模式继续关闭。

- GPU 混合负载已完成：受控持锁 130 秒，图片与标签保持排队，释放后视频/图片/标签全部成功。不是八分钟推理基准。完整接入、质量结论、边界与证据见 [本轮验收报告](ltx-accept-20260921/README.md)。

## App 界面接续验收（2026-09-21）

- 已在现有 HarmonyOS 模拟器覆盖安装最终签名 HAP，保留登录态；当前无连接真机。
- 修复模型切换缺失、LTX 单图/提示词说明不符、未准入状态歧义、再次创作拒绝提示落在长列表底部的问题。
- 实际验证模型切换与刷新、规格、未验收禁用、不支持入口、4条历史作品、在线与缓存播放到结尾、停止播放、再次创作拒绝；9项布局断言通过，构建成功。
- 新生成/素材上传/排队取消未验证，服务端门禁保持关闭。不是 LTX App 全链路通过。见 [App 验收报告](../2026-09-21-video-app/README.md)。

## 1. 当前状态

- 视频服务与 Scheduler 当前发布 `video-ltx-20260921-v2`、包 `video_generate/1.1.1`。作品保留；全部模式未准入。
- 正式六样例的旧“色偏通过”仅是均值量测结果，已被逐帧 AI 质量复核否决。
- LTX 正式接入、连续任务和混合 GPU 负载已完成；下一步解决固定镜头/局部动作遵循，并使用新的保留素材组重新验收。完整 VideoService 资产链路与真机验证尚未扩展到 LTX；其他五模式仍需独立验收。

## 2. 版本沿革

任务包不可变：任何行为改动都升版本 + 用迁移把步骤钉到新版本（并同步注册结果 schema）。

| 包 | 发布 | 迁移 | 改了什么 | 为什么 |
|---|---|---|---|---|
| `1.0.0` | `video-production-20260914-v1` | V155 / V156 | 首版：6 个模式骨架、共享 GPU 租约、固定工作流、中性灰补边、49 帧 832×480 16fps | P0 窗口结论（仅首帧可用） |
| `1.0.1` | `video-production-20260919-v1` | V157 / V158 | 出片**与封面**都按控制帧量出的几何把两侧补边盖回 `0x808080`；结果记录 `padRects` | 模型会把补边改成任意颜色（同一素材一次蓝一次黄） |
| `1.0.1` | `video-production-20260919-v2` | V158 | 无包变更：修"结果 schema 未注册"与发布脚本可重复性 | 见 §4.4、§4.8 |
| `1.0.2` | `video-production-20260919-v3` | V159 | 控制融合强度 **α 0.25 → 0.50**，并可用 `VIDEO_FILL_BLEND` 覆盖 | P0 [blend 扫描](p0-window-20260914T112215Z/blend-sweep.txt)：α=0.25 帧均亮度偏离源图 −0.037，0.50 只 −0.013 且运动量几乎不变；0.75/repeat 已 FROZEN |
| `1.0.3` | `video-production-20260919-v4` | V160 | 出片**前**把每帧内容区三通道均值平移对齐首帧（`VIDEO_COLOR_MATCH`，默认 1.0），随后补边照旧盖回中性灰；结果记录 `colorMatch` / `colorOffsetLast` | α=0.50 仍不够（seed 932 仍漂 115），色偏来自模型先验且与种子相关 |

## 3. 验收结论矩阵（对照 acceptance.md）

| ID | 模式 | 素材 | 当前结论 | 证据 |
|---|---|---|---|---|
| I01 | 单图首帧 | 1 图 | **质量拒绝、已关闭**（AI 3/6 有限通过，3 条严重变色） | [p0-reaccept-20260920](p0-reaccept-20260920/README.md)、[p0-reaccept-20260919](p0-reaccept-20260919/README.md)（修复前对照） |
| T01 | 文生 | 无 | P0：有限/不可用（未见中间档） | [report.md](report.md)、[p0-window-*](.) |
| R01/R02 | 两图/三图主体 | 2–3 图 | 未验收（`REFERENCE_CONTROL_UNVERIFIED`） | 同上 |
| F01 | 首尾帧 | 2 图 | 未验收（`MODE_VALIDATION_REQUIRED`） | 同上 |
| V01 | 整体重绘 | 1 视频 | 未验收（`SOURCE_QUALITY_UNVERIFIED`） | 同上 |
| M01 | 局部修改 | 视频+蒙版 | 未验收（`LOCALITY_DISPUTED`，指标与 VLM 结论冲突未决） | 同上 |
| 服务项 | B01–B12 | — | 见 [p1-server-app.md](p1-server-app.md) 与本文件 §4；跨账户隔离、取消、Range 播放、回退等已验证 | `p1-server-app.md`、`p1-yuyutian-verification/` |

产品侧：App「视频工作台」三个入口（文字创作 / 图片动起来 / 视频改编），未验收模式在 UI 上禁用并显示原因码；
首帧素材支持**本地选图**与**用我的图片作品**（后者直接把 MyTools 生图结果当首帧，不经过设备相册）。

## 4. 修过的问题（含我自己引入的回归）

1. **生产部署缺陷**（首版上线时）：drop-in 命名排序导致发布静默失效；替换 `ExecStart` 丢掉其它发布的
   配置片段与集群白名单；执行器子进程环境是白名单制、`video_generate` 变量没声明；执行器 venv 缺 numpy；
   服务依赖 `outputs/<jobId>/result.json`。均已修，见 `p1-server-app.md`。
2. **部署漂移**：生产执行器 jar 仍是 Sep 13 的旧构建（缺 4 行 `script-environments` 默认值）。
   按"成员内容摘要"审计后对齐，并把审计方法留下来（`ec35340e`）。
3. **包根基线取错（我引入）**：做视频发布时把脚本包根取成了 image-production 的旧包根，只带
   `image_generate 1.0.0`，而任务定义钉 1.1.2 → 图片任务永远派发不出去（排队 5 分钟超时）。
   修法：基线改为从**运行中执行器**取实际包根，并入历史里程碑的新版本，并加"被钉版本预检"。
4. **结果 schema 未同步注册（我引入）**：包新增 `padRects` 而调度器里的 `task_definition.result_schema`
   还是旧的 → 任务跑满 8 分钟推理后才以 `TASK_RESULT_SCHEMA_INVALID` 失败。修法：V158 同步注册 +
   把"包 schema == 注册 schema"做成 **activate 阶段**硬预检（放 stage 会先有鸡还是先有蛋）。
5. **executor journal 门禁把节点拖成 OFFLINE**：那条被拒的上报变成 `DIAGNOSTIC`，执行器据此**既拒绝领任务
   又心跳失败**。查明原因、任务终态后清理该记录，节点恢复（`diagnostic` 只保留 ACKNOWLEDGED）。
6. **补边被模型改色**：控制端是中性灰，成片却是蓝/黄 → `1.0.1` 出片+封面同时还原补边。
7. **内容区颜色漂移**：人工视角复核发现"补边是灰的、内容却漂色"（冷白底 R−B 变化 −109~−176，
   暖色/风景整体变暗 −13~−39）→ `1.0.2` 调 α + `1.0.3` 出片后颜色校正；修复后色偏 ≤5、亮度 Δ ≤±1.6。
8. **发布脚本只支持首次发布**：env 已存在即拒绝、每次重新生成共享令牌、基线已含同版本包导致 `copytree`
   撞车、`current_executor_jar()` 在 drop-in 指向半成品发布时直接失败 → 全部修成可重复发布
   （同版本必须同内容、令牌沿用上一版、jar 有兜底、发布名可 `VIDEO_DEPLOY_NAME` 覆盖）。
9. **App 素材行"窜行"**：加第三个按钮后标签被挤成竖排 → 标签独占一行、按钮另起一行（`40639216`）。
10. **验收判据本身太弱**：原来只看边缘**亮度**（只抓白边，蓝边 0.36、黄边 ~0.6 都能过）→ 升级为
    "补边必须中性灰（亮度带 + 通道离散）"；随后又发现"色偏"要按**色平衡**判而不是单通道幅值
    （否则把"整体变暗"误判成色偏），最终判据为色平衡变化 ≤ 40/255。

## 5. 待办

| 事项 | 谁做 | 说明 |
|---|---|---|
| AI 评分 | 已完成 | 正式 3/6 有限通过、整体拒绝，见 AI 复核报告；无需用户人工打分 |
| 连续 10 任务稳定性 | 已完成 | 10/10 服务成功，恢复完成；不代表画质合格 |
| 原生 I2V 候选验证 | 我 | 先验证 LTX-Video 2B distilled 的资源适配和同样例质量，再决定是否接入 |
| 其余 5 个模式验收 | 我 | 按独立样例和标准验证，不随首帧结果自动开放 |
| B06 实际混合 GPU 负载 | 我 | 调度逻辑测试通过；后续随真实长视频窗口补测图片/标签排队与恢复 |
| `media_submit_analysis:1.0.0` schema 非法 JSON | 媒体线 | 视频发布预检已收窄到只管本发布的包，但那个包自身值得修 |
| 公网脚本访问 | 你 | Python 直连公网被 Cloudflare Error 1010 拦（App 走系统栈不受影响） |
| 真机验证 | 你 | 模拟器输入注入不稳、相册保存在本机 SDK 不可行（`WRITE_IMAGEVIDEO` 系统级、无 SaveButton）；真机可补 |

## 6. 运维要点

**环境变量（`/opt/yuyutian/mytools/config/video-generation.env`，改完需重启对应单元）**

| 变量 | 当前值 | 作用 |
|---|---|---|
| `VIDEO_FILL_BLEND` | `0.50` | 首帧控制融合强度（运维可覆盖，非法值任务直接失败） |
| `VIDEO_COLOR_MATCH` | `1.0` | 出片后内容区颜色校正强度，`0` 关闭 |
| `VIDEO_FIRST_FRAME_VALIDATED` | `false` | 逐模式开关（其余模式为 false） |
| `VIDEO_GENERATION_ROUTE_ENABLED` | `true` | 网关路由开关 |
| `VIDEO_MIN_FREE_MIB` | `14848` | 取租约前的最低空闲显存（MiB） |
| `VIDEO_INFERENCE_TIMEOUT_SECONDS` | `2400` | 推理超时上限 |
| `VIDEO_GPU_LOCK_FILE` | `/opt/yuyutian/mytools/runtime/image-generation/gpu.lock` | 与图片/标签共享的显存锁 |

**发布流程**（root，在 Ubuntu 上）：

```bash
VIDEO_DEPLOY_NAME=video-production-<日期>-v<N> python3 deploy_video_generation.py stage
python3 deploy_video_generation.py activate      # 应用迁移、装 drop-in、重启相关单元、契约预检
python3 deploy_video_generation.py enable        # 打开网关路由
python3 deploy_video_generation.py verify        # 维护窗口内跑一条真实任务并恢复原状
```

`set_mode_flags(['FIRST_FRAME'])` 用于按验收结果开关模式（会重写 `executor.properties` 并重启执行器与视频服务）。

**维护窗口要点**：暂停含 GPU 步骤的任务定义（`image_generate`、`media_generate_tags`，容量置 0）→ 排空在途任务
→ 停 `mytools-image-comfy` 腾内存（视频运行时要求 ≥14.5 GiB 空闲）→ 跑验收 → 恢复容量与图片 Comfy。
`activate` 自带内存门槛，低于 14 GiB 会拒绝启动视频运行时。

**验收与复核命令**

```bash
# 6 样例验收（3 主体 × 2 种子，自动量测补边中性度/内容区色偏/运动量，产出 acceptance.json）
VIDEO_DEPLOY_NAME=<release> python3 service/deploy/accept_video_first_frame.py \
  --subjects a.png:冷白底:A-cold-white b.png:暖色生成:B-warm-generated c.png:风景照:C-landscape \
  --output /opt/yuyutian/mytools/runtime/<release>/acceptance-<日期>
# 评分汇总（只算 role=acceptance 的行；关键项≥3、≥5/6 且无一票否决才准入）
python3 service/deploy/summarize_video_review.py --sheet <表>.csv [--machine-prescore] [--write]
```

**提交前必做的两道预检**（都在 `deploy_video_generation.py` 里）：`assert_pinned_packages_indexed()`
（已启用任务定义钉的每个包版本必须在发布索引里）与 `assert_result_schemas_registered()`
（包里的结果 schema 必须与调度器注册的一致，activate 阶段在调度器健康之后执行）。

## 7. 关键事实速查

- 端口：视频服务 23341 ｜ 调度器 23410 ｜ 网关 23200 ｜ 视频 Comfy 8190 ｜ 图片 Comfy 8189 ｜ Ollama 11434
- 运行时目录：`/opt/yuyutian/mytools/runtime/video-generation/`（uploads / outputs / work），日志
  `/opt/yuyutian/logs/mytools/`
- 模型与工作流：`wan2.1-vace-1.3b-fp16`、`video-vace-1.3b-v1`、固定 832×480 / 49 帧 / 16 fps / 无音轨
- 账户：历史样例在 owner 1（admin）；`yuyutian` 名下有 4 条视频作品（含 App 界面提交的那条）
- 代表性任务：`31655ccd`（P1 首次真实链路）、`0e5a60b8` / `edf2035b`（接口层验证）、`db51f053`（App 界面提交）、
  `44fe02fb` / `46ab1168` / `a97a45c2` / `f4d2d139` / `20323017` / `edc9dc52`（最终验收 6 条）

## 8. 本线提交清单（`git log --oneline`）

| 提交 | 内容 |
|---|---|
| `f4530502` | feat(video)：视频生成工具（服务 + 网关 + 调度器 + 部署 + App） |
| `ec35340e` | docs(video)：部署漂移审计与执行器 jar 对齐 |
| `45a875a8` | docs(video)：yuyutian 账号在虚拟机上的验证与生产链路 |
| `dade1e6d` | feat(video)：视频工具支持"用我的图片作品"当首帧 |
| `40639216` | fix(video)：素材行不再把标签挤成竖排 |
| `4580ebab` | docs(video)：App 提交跑通与补边颜色漂移的首次记录 |
| `4258ed5a` | fix(video)：还原被模型改色的中性灰补边（1.0.1） |
| `af70d968` | chore(deploy)：发布名可按里程碑覆盖 |
| `03d19ebb` | fix(deploy)：发布可重复 + 验收以中性灰为硬判据 |
| `832bf44d` | docs(video)：补边修复后的首次重新验收 |
| `4a32632b` | docs(video)：人工评分细则（对齐业界标准）+ 可填评分表 |
| `84a8948f` | docs(video)：逐模式评分锚点 + 机器预评 |
| `e068dd42` | fix(video)：修复人工复核暴露的内容区颜色漂移（1.0.2/1.0.3） |
| `62e0bbe6` | feat(video)：补上 1.0.2 包与 V159（已在生产运行） |

## 9. 证据索引

| 目录 | 内容 |
|---|---|
| [report.md](report.md) | P0 总报告（各模式结论、方法、边界） |
| [p1-server-app.md](p1-server-app.md) | P1 服务端 + App 上线记录、部署缺陷、部署漂移审计 |
| [p1-live-run/](p1-live-run/) | P1 首次真实链路成片与量测 |
| [p1-vm-verification/](p1-vm-verification/) | 模拟器走查（界面、能力目录、门禁） |
| [p1-yuyutian-verification/](p1-yuyutian-verification/) | yuyutian 账号验证：VM 登录、在线播放、生图当首帧链路 |
| [p0-reaccept-20260919/](p0-reaccept-20260919/) | 补边修复后的验收（**修复前**的颜色漂移证据，含三联图） |
| [p0-reaccept-20260920/](p0-reaccept-20260920/) | 历史均值指标验收（6/6 服务成功），画质已被 AI 复核否决 |
| [p0-window-*](.) | P0 各临时窗口的原始产物（含 blend 扫描、边缘剖面、VLM 复核） |
| [vlm-review/](vlm-review/) | 历史 VLM 与新增 AI 复核（`notHumanReview=true`，按来源分别保留） |
| [coordination-verification/](coordination-verification/) | GPU 协调与租约验证 |
| [INDEX.md](INDEX.md) | 上述目录的逐项索引 |
