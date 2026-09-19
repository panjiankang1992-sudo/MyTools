# 视频生成（Wan 2.1 VACE 1.3B）进展与状态

> 更新：2026-09-20。本文件是这条线的**单一入口**：当前状态、版本沿革、验收结论、修过的坑、
> 待办与运维要点。细节与原始证据在 `docs/verification/2026-09-14-video-generation/`，
> 验收标准在 `docs/research/2026-09-14-video-generation/acceptance.md`。

## 1. 一句话状态

- **生产**：发布 `video-production-20260919-v4`，任务包 `video_generate/1.0.3`；
  能力目录里**只有单图首帧（FIRST_FRAME）对外开放**，其余 5 个模式标未验收、禁止提交。
- **服务**：7 个单元（调度器 / 执行器 / 网关 / 视频服务 / 图片服务 / 视频 Comfy / 图片 Comfy）全部 active；
  执行器 ONLINE，声明 `video_generate` 的 1.0.1 / 1.0.2 / 1.0.3；GPU 空闲（<800 MiB）。
- **客观验收（最新）**：3 主体 × 2 种子 = 6 条正式样例，**6/6 成功**；补边中性灰 0.502（离散 0.0）、
  内容区色偏 ≤5（判据上限 40）、运动量 5.3–16.7（下限 1.5）、单条耗时 494–497 s。
- **未完成**：**人工评分**（已有机器预评 `accept`，等人确认）、`acceptance.md` 要求的
  **连续 10 任务稳定性补测**、**其余 4 个模式**的验收。

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
| I01 | 单图首帧 | 1 图 | **客观通过**（6/6：色偏 ≤5、补边 0.502、运动在带内）；**人工评分待做** | [p0-reaccept-20260920](p0-reaccept-20260920/README.md)、[p0-reaccept-20260919](p0-reaccept-20260919/README.md)（修复前对照） |
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
| 人工评分 | **你** | 表已按新证据填好机器预评（6 条全绿、结论 accept）：`p0-reaccept-20260920/review-sheet.csv`；改完跑 `python3 service/deploy/summarize_video_review.py --sheet <表> --write` 合并进 `vlm-review/reviews.json`。判据见 [human-review-rubric.md](../research/2026-09-14-video-generation/human-review-rubric.md) 与 [per-mode-review-anchors.md](../research/2026-09-14-video-generation/per-mode-review-anchors.md) |
| 连续 10 任务稳定性补测 | 我 | acceptance.md 要求；需一次 GPU 窗口（约 1.5–2 h） |
| 其余 4 个模式验收 | 你定优先级 | 每个模式都要独立窗口；T01 的 M01 还有"指标 vs VLM"冲突未决 |
| 运动量取舍 | 你 | 修色偏后运动量从 12.6~29.5 降到 5.3~16.7（α=0.50 的代价）。若想更活泼，可试 α=0.35 再跑一次窗口（颜色校正会兜住色偏） |
| `media_submit_analysis:1.0.0` schema 非法 JSON | 媒体线 | 视频发布预检已收窄到只管本发布的包，但那个包自身值得修 |
| 公网脚本访问 | 你 | Python 直连公网被 Cloudflare Error 1010 拦（App 走系统栈不受影响） |
| 真机验证 | 你 | 模拟器输入注入不稳、相册保存在本机 SDK 不可行（`WRITE_IMAGEVIDEO` 系统级、无 SaveButton）；真机可补 |

## 6. 运维要点

**环境变量（`/opt/yuyutian/mytools/config/video-generation.env`，改完需重启对应单元）**

| 变量 | 当前值 | 作用 |
|---|---|---|
| `VIDEO_FILL_BLEND` | `0.50` | 首帧控制融合强度（运维可覆盖，非法值任务直接失败） |
| `VIDEO_COLOR_MATCH` | `1.0` | 出片后内容区颜色校正强度，`0` 关闭 |
| `VIDEO_FIRST_FRAME_VALIDATED` | `true` | 逐模式开关（其余模式为 false） |
| `VIDEO_GENERATION_ROUTE_ENABLED` | `true` | 网关路由开关 |
| `VIDEO_MIN_FREE_MIB` | `14848` | 取租约前的最低空闲内存 |
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
| [p0-reaccept-20260920/](p0-reaccept-20260920/) | **颜色漂移修复后的最终验收**（6/6、色偏 ≤5） |
| [p0-window-*](.) | P0 各临时窗口的原始产物（含 blend 扫描、边缘剖面、VLM 复核） |
| [vlm-review/](vlm-review/) | 14 条自动 VLM 复核（`notHumanReview=true`，人工评分将合并进 `reviews.json`） |
| [coordination-verification/](coordination-verification/) | GPU 协调与租约验证 |
| [INDEX.md](INDEX.md) | 上述目录的逐项索引 |
