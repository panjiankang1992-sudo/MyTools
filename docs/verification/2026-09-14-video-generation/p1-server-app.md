# P1 视频生成：服务端与 App 接入

日期：2026-09-14（P0 之后）。上游结论与验收边界见 [report.md](report.md)，
证据索引见 [INDEX.md](INDEX.md)。

**一句话状态**：已部署到生产并**跑过一次经过新链路的真实任务**（`FIRST_FRAME`，49 帧，
推理 487 s、显存峰值 14543 MiB，成片与封面已登记资产）。补边修复经本次运行确认生效
（边缘亮度 0.50 而非 P0 的 0.94–0.98），`FIRST_FRAME` 已打开且网关路由已启用。
其余模式仍未开启。证据见 [p1-live-run](p1-live-run/README.md)。
**后续更正**：亮度判据不足——模型会把灰补边改成蓝/黄，已发 `video_generate/1.0.1` 修复并按
3 主体 × 2 种子重新验收（6/6、补边 0.502、离散度 0.0），见
[p0-reaccept-20260919](p0-reaccept-20260919/README.md)。

## 1. 这一轮做了什么

P0 结束时只有 `tools/` 下的实验脚本和一个手动窗口流程，没有服务、没有接口、没有 App 入口。
P1 把它补成一条可运维的产品链路：

| 层次 | 新增内容 |
|---|---|
| 业务服务 | `service/video-generation-service/`（Java 21 + Spring Boot）：素材上传、幂等任务、能力目录、调度派发、结果对账、资产登记、用户隔离 |
| 执行器任务包 | `packages/video_generate/1.0.0/`：显存租约、VACE 控制信号预处理、固定工作流绑定、推理轮询、MP4 编码 |
| 工作流规格 | `tools/emit_workflow_specs.py`：把已验证的构图代码固化成"不可变规格 + 精确绑定 + 节点白名单 + 摘要" |
| 网关 | `VideoGenerationGatewayController`：`/api/app/v1/video-generation/**`，所有者只来自登录态 |
| 调度器 | `V155/V156` 迁移 + `VideoDeploymentController`：`video_generate` 任务定义、`video-generation` 集群、发布审计 |
| 部署 | `service/deploy/deploy_video_generation.py`（stage/activate/enable/verify/rollback）+ `services.json` 登记 |
| App | 「视频生成」工具页（模式、素材、提交、轮询、取消、播放、作品历史） |

## 2. 关键设计决定

**客户端不能提交节点图。** 工作流由 `emit_workflow_specs.py` 从 `tools/workflow.py` 的已验证构图
代码生成：它会注入哨兵值反查每个可变字段落在哪个节点输入上，因此规格里的绑定不可能和构图代码漂移，
任何构图改动都会改变规格哈希。执行器启动时先校验索引摘要、再校验单模式规格摘要、再校验节点类型白名单。

**素材走原始字节，不走 base64。** 视频 200MB 用 base64 会膨胀 33% 且要两次全量拷贝；网关的
两个上传端点直接转发原始字节，网关层在 MVC 读体之前用 `Content-Length` 预检上限。

**模式级验收开关 + 引擎级门禁。** `VIDEO_LOCAL_VALIDATED` 与 `VIDEO_GPU_COORDINATION_VALIDATED`
是引擎门禁，逐模式开关只在这两个都打开时才可能生效；未验收模式仍然出现在 `/models` 里，
带 `reasonCode`，App 显示为"未验收"并禁止提交。**"目录说不可用、创建却成功"是一个真实出现过的
缺陷**（见 §4 DEF-1），现在目录状态与创建校验共用同一个判断。

**音轨整体不可用。** P0 没有验证任何音轨封装路径，因此 `KEEP_SOURCE_AUDIO` 一律以 `VIDEO_002`
拒绝，而不是产出一段静音视频冒充成功；`result.json` 里 `hasAudio` 恒为 false。

**补边颜色从白改中性灰（本轮唯一的模型侧改动）。** P0 的 VLM 复核发现：`scale + pad=color=white`
把 1024×1024 方图塞进 832×480 时左右各留 60 列近白，而首帧是"保留帧"，白条因此**整段留在成片里**
（输出边缘长期维持 0.94–0.98），影响所有图片条件模式。现在两处实现（`tools/prepare_control.py`
与任务包 `scripts/vace_control.py`）都改用与官方 `frameref` 一致的中性灰 `0x808080`，
并由 `test_worker.py::ControlDriftTest` 逐帧比对两套实现，防止再次分叉。

**帧数与尺寸固定为 49 帧 / 832×480 / 16fps。** 81 帧实测峰值 15436/16311 MiB，只剩 875 MiB，
不适合放进无人值守的生产路径；竖屏未验证，因此不列出。

## 3. 本地验证证据（全部真实执行）

| 验证 | 命令 | 结果 |
|---|---|---|
| 视频服务编译 | `mvn -o -q -f service/video-generation-service/pom.xml compile` | 通过 |
| 视频服务单测 | `mvn -o -f service/video-generation-service/pom.xml clean test` | **33/33 通过**（能力目录 8 + 业务 21 + Range 解析 4） |
| 任务包单测 | `cd packages/video_generate/1.0.0/tests && python3 -m unittest test_worker` | **33/33 通过** |
| **执行器端到端演练** | `cd packages/video_generate/1.0.0/tests && python3 -m unittest test_end_to_end` | **4/4 通过**：用一个假 ComfyUI 真实跑完门禁→素材摘要与解码预检→控制信号预处理（真 ffmpeg）→显存租约（替身 nvidia-smi）→规格绑定→提交轮询→取回 49 帧→编码 MP4/封面→写 `result.json`；断言成片是 832×480/49 帧 H.264、绑定值与上传文件名正确、中间产物已清理，并覆盖"未验收模式不得提交""推理失败返回稳定码""摘要不符拒绝"三条路径 |
| 控制信号一致性 | 同上 `ControlDriftTest` | 任务包与 `tools/prepare_control.py` 逐帧一致 |
| 网关 | `mvn -o -f service/mytools-gateway/pom.xml test` | **148/148 通过**（含 15 项视频用例 + 2 项票据路由用例） |
| 调度器发布控制 | `-Dtest=VideoDeploymentControllerTest,ImageDeploymentControllerTest` | 2/2 通过 |
| 任务契约 | `-Dtest=VideoTaskContractTest` | **4/4 通过**：参数 Schema 接受服务真实派发内容、拒绝内部字段；结果 Schema 与任务包 Schema 逐字段一致；执行器真实结果被接受、内部路径被拒绝 |
| 迁移可执行性 | 在生产库的**结构副本**（临时库，测完即删）上执行 `V155`/`V156` | 通过；`video_generate` 任务、`video-generation` 集群、步骤定义、审计表均按预期落库，两个 Schema 均 `JSON_VALID=1` |
| 任务包发布 | `python3 service/scripts/assemble_executor_packages.py --service-root service --output <tmp>` | 通过，`video_generate/1.0.0` 进入包索引（共 136 个包） |
| 部署脚本 | `python3 -m py_compile` + `service/deploy` 既有测试 | 通过；`services.json` 新增 23341 |
| 视频服务打包 | `mvn -o -q -f service/video-generation-service/pom.xml package -DskipTests` | 通过；jar 内含 `db/migration/V1__create_video_jobs.sql` 与 `application.yml` |
| App 全量构建 | `hvigorw clean` 后 `hvigorw --mode module -p module=entry@default -p product=default assembleHap --no-daemon` | **BUILD SUCCESSFUL，0 错误 0 警告**，产出 `entry-default-signed.hap` |
| 生产 Range 验证 | 对已产出成片直连视频服务发 `Range` 请求 | 200 带 `Accept-Ranges: bytes`；`bytes=100-199` → **206 + `Content-Range: bytes 100-199/159252` + 恰好 100 字节**，且与整段同偏移逐字节一致；`bytes=999999-` → **416 + `bytes */159252`** |
| 生产路由验证 | 网关（未带登录态） | 两个前缀都 401；32 位十六进制票据路径 **404（免登录可达）**；`tickets/short` 401（不会被当作播放路径放行） |
| App 静态检查 | `scripts/test-authorized-api-policy.sh`、`test-download-stream-integrity.sh`、`test-tool-service-response-policy.sh` | 全部通过 |
| App 既有失败（与本次无关） | `scripts/test-ui-design-policy.sh` | 失败在电子书模式文案（`ebook modes must remain 书源/远程/本地`）；同一文件里有其他未提交的电子书改动，去掉本次改动后仍失败 |

任务包单测覆盖的失败路径包括：未验收模式、GPU 协调门禁、尺寸/帧数/帧率/种子/提示词越界、
原声策略、素材角色顺序、上传文件摘要不符、上传目录中的符号链接、截断图片、
掩码视频二值化、产物路径逃逸、非 PNG 产物、上传文件名穿越、规格摘要不符、未声明绑定、
节点白名单、以及"第二张参考图不被静默丢弃"。

## 4. 测试暴露并已修复的缺陷

| 编号 | 缺陷 | 影响 | 修复 |
|---|---|---|---|
| DEF-1 | `VideoCapability` 的模式开关漏掉 GPU 协调门禁 | 目录显示 `UNVERIFIED`，但 `create` 会放行任务 | 目录状态与 `require()` 共用 `engine()` 判断；新增回归用例 |
| DEF-2 | `create()` 无事务：输入校验失败仍留下 QUEUED 任务行与部分输入行 | 会被 `reconcile()` 派发成空任务 | 加 `@Transactional`，任务与输入一起回滚 |
| DEF-3 | `KEEP_SOURCE_AUDIO` 的"必须有源视频"分支永远走不到 | 契约含糊 | 只保留"取值非法 → `VIDEO_001`"与"能力未验收 → `VIDEO_002`"两条清晰路径 |
| DEF-4 | 未使用的 `ArrayList` 导入 | 规范告警 | 删除 |
| DEF-5 | 并发幂等冲突时 `getFirst()` 可能抛 `NoSuchElementException` | 变成 503 而不是幂等重放 | 判空后返回 `VIDEO_008` |
| DEF-6 | 端到端演练暴露：`/proc/meminfo` 读取失败会让**已经编码成功**的任务整体失败 | 诊断信息反过来毁掉已完成的产物 | 资源证据改为 best-effort，读不到返回 0 |
| DEF-7 | 轮询期的 `nvidia-smi` 采样失败会中断正在跑的推理 | 一次采样抖动就白烧一个窗口 | 采样失败只跳过本次读数 |
| DEF-8 | `durationMs` 用 Python `round` 的银行家舍入，49 帧得到 3062 而非 3062.5→3063 | 少报时长 | 改为四舍五入取整 |
| DEF-9 | 网关错误码与服务端语义不一致（网关自定义了一套编号） | App 会把"模式未验收"显示成"输入无效" | 网关码表统一为与服务端一致的 `VIDEO_001..009`，另加 `VIDEO_010`（服务暂不可用）；业务服务把能力不支持改为 422，网关按状态码还原 |
| DEF-10 | 调度器未登记 `video-generation-service` 业务令牌，也未保护视频发布路径 | 视频任务派发会 401、发布接口无鉴权 | 补 `business-clients` 绑定与 `isProtectedPath`（含回归用例） |

## 5. 上线记录（2026-09-18/19 实际执行）

发布目录 `video-production-20260914-v1`。因为到主机的隧道只有约 20KB/s，发布包用"基线 jar + 增量"
方式交付（`build_release_delta.py` / `assemble_release_delta.py`）：只传变化的 24/6/7 个成员
（共约 156KB），在目标机上重组并**逐成员校验**，重组结果与本地构建的 jar 逐条目一致
（含 35/38 个目录条目）。整包 528KB、传输 19 秒；若整包传输需要一小时以上且中途会断。

实际执行顺序：`assemble_release_delta` → `stage` → `activate` → `verify`（维护窗口）→ `enable`。
窗口期间含 GPU 步骤的 8 个任务定义被暂停，结束后逐项恢复（并已在窗口异常退出后手动确认恢复）。

## 6. 真实部署暴露并已修复的缺陷

上线过程本身找到了 5 个只有真跑才会暴露的问题，全部已修复并重新验证：

| 编号 | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| DEF-11 | drop-in 文件名 `zz-` 排在既有 `zzz-/zzzz-` 之前 | 发布**静默不生效**：三个共享服务仍跑旧 jar | 改名 `zzzzz-`，并在安装后断言生效 jar 属于本发布 |
| DEF-12 | 替换 ExecStart 时丢掉了其它发布的 `--spring.config.additional-location` 片段，且集群/标签只写自己那一条 | 执行器同时声明 image-generation 与 video-generation，但调度器只放行后者 → 节点注册 **403**，`executor-remote-1` 掉线，调度器健康转 DOWN | 汇总既有授权取并集（`merge_authorization`），并保留当前生效的全部配置片段后追加自己的 |
| DEF-13 | 任务包进程的环境是执行器"从头构造"的白名单，不继承执行器环境 | 执行器侧一切正常，但 worker 拿不到 `VIDEO_*`，以 `VIDEO_LOCAL_VALIDATION_REQUIRED` 失败 | 用 `executor.script-environments.video_generate.*` 显式声明（与 image-extension 同一机制），并把模式开关同步写入服务与执行器两侧 |
| DEF-14 | 执行器 venv（Python 3.14）没有 numpy | worker 以 `ModuleNotFoundError: numpy` 失败 | `activate` 增加 `ensure_task_runtime()`：检测并安装 `numpy==2.5.3`；默认索引在本网络会卡死，回退清华镜像（实测 43MB/s） |
| DEF-15 | 执行器不写 `outputs/<jobId>/result.json`，服务却读它并把 `resourcePeak` 直接序列化 | 任务产出成功却卡在 `PERSISTING`/`VIDEO_006` | 服务改为"证据可选"：帧数/尺寸取任务参数、时长取探针，`resourcePeak` 缺失时写空对象；同时把资产来源改按类型区分编号（封面与成片共用 `sourceBusinessId` 会 409） |

DEF-15 的两个子问题分别可见：补丁后 `result_json` 变为
`{"frames":49,"fps":16,"durationMs":3063,"width":832,"height":480,"resourcePeak":{},"hasAudio":false}`，
封面资产也登记成功（`COVER 286310` 字节）。执行器侧的完整证据（含 `gpuPeakMiB=14543`、
`inferenceMillis=487125`）保留在调度器的步骤结果里。

## 7. Range 播放与原片对比（本轮补充）

目标要求"MP4/H.264/faststart 与封面/资产落库及 **Range 播放**"，以及 App 侧的"**原片对比**"。
补齐方式：

- **服务端**：`ByteRange` 解析单区间（`start-end` / `start-` / `-suffix`），命中回 206 并带
  `Content-Range`，越界回 416，其他形态按完整响应降级；读取用 `FileChannel` **只读请求窗口**，
  不为一次播放把整段（素材上限 200MB）读进堆。成片与上传素材都支持 Range。
- **网关**：读成片/素材的端点改为**流式转发**（`HttpURLConnection` 逐字节转发，保留
  `Content-Type`/`Content-Length`/`Content-Range`/`Accept-Ranges`，强制 `Accept-Encoding: identity`
  以免压缩破坏偏移），不再把整段缓冲成 `byte[]`。
- **票据**：原生播放器（AVPlayer / `Video` 组件）无法附加登录头，因此按仓库既有的媒体播放做法
  新增 `VideoPlaybackTicketService`：`POST /jobs/{id}/ticket`、`POST /uploads/{id}/ticket` 用登录态
  换取两小时票据，`GET /tickets/{token}` 免登录取流且**只绑定签发者的那一个资源**。
- **路径**：方案文档写的是 `/api/video-generation/**`，仓库约定是 `/api/app/v1/<tool>/**`，
  两个前缀现在都指向同一批处理器（控制器 `@RequestMapping` 数组 + 过滤器同时登记）。
- **App**：作品卡片新增「在线播放」（票据 URL + 播放器按 Range 边下边播）与「对比原片」
  （源视频模式用 `SOURCE_VIDEO` 素材签票播放），本地下载播放保留为离线路径；
  模式区上方新增**三个入口卡片**（文字创作 / 图片动起来 / 视频改编），入口只做筛选、不改变服务端契约，
  自动落到第一个有已验收模式的入口，入口下没有可用模式时保持不可提交。

## 8. 还差什么

1. **yuyutian 账号验证已完成**（虚拟机登录 + 该账号下跑通全链路含 Range 播放）：
   见 [p1-yuyutian-verification](p1-yuyutian-verification/README.md)。仍未做到的是"在 App 里点选图再提交"
   ——模拟器图库为空且无法注入图片（目录不可写、无 root、mediatool 不支持导入、无相机应用），
   提交因此改由 App 背后的同一批接口驱动。
2. **App 虚拟机走查已做，但没提交真实任务**：2026-09-19 在本地 HarmonyOS 模拟器上走查了入口、
   能力目录、三入口与逐模式原因码、素材角色与提交门禁、作品空态与所有者隔离
   （截图与结论见 [p1-vm-verification](p1-vm-verification/README.md)）。
   **仍未验证**：加载/排队/取消/进度状态，以及成片播放与「对比原片」——播放需要当前账号有一条
   已成功作品，而该账号一条都没有，且仓库的 VM 验收约定不产生内容。
   播放链路的间接证据：服务端 Range 在生产上实测 206/416 且区间内容逐字节一致、网关票据路由
   在生产上 401/404 行为正确、网关 15 项用例覆盖转发与票据绑定。
3. **`video_generate/1.0.0` 不写 `outputs/<jobId>/result.json`**：服务已能自给自足（DEF-15），
   但工具的 `result_json.resourcePeak` 仍为空；如需在工具侧展示显存峰值，需要发布
   `video_generate/1.0.1`（把执行器回报的结果同时落一份到受管输出目录）并更新任务定义版本。
4. 其余模式（文生、两图参考、首尾帧、结构重绘、局部修改）保持关闭，直到各自在维护窗口里
   拿到与 acceptance.md 对齐的结论。
5. **补边几何只重跑了一次**：P0 的 3 主体 × 2 种子网格是白边几何下的结论；本次确认了缺陷消失，
   但没有重跑六格网格，也**没有人工看片**。—— 这一条已在
   [p0-reaccept-20260919](p0-reaccept-20260919/README.md) 补齐（3 主体 × 2 种子、49 帧、客观判据
   全绿）；**人工评分仍未做**。

## 9. 部署漂移审计（2026-09-19）

提交代码后核对"生产上跑的字节 = 仓库 HEAD 构建的字节"。做法是按**成员内容**计算 jar 的聚合摘要
（不依赖 zip 时间戳/压缩方式，只反映每个成员解压后的内容），本地构建与生产发布目录各算一次：

| jar | 审计前 | 处理后 |
|---|---|---|
| `video-generation-service.jar` | 与 HEAD 一致 | 一致 |
| `mytools-gateway.jar` | 与 HEAD 一致 | 一致 |
| `task-scheduler-service.jar` | 与 HEAD 一致 | 一致 |
| `task-executor-service.jar` | **漂移**：复用的是 Sep 13 的旧构建，缺少 HEAD 里 `script-environments.image_generate` 的 4 行默认值（`IMAGE_PROMPT_VALIDATED`、`IMAGE_GENERATION_EDIT_VALIDATED`、`IMAGE_EDIT_WORKFLOW_FILE/SHA256`） | 已用 1 个成员的增量把 HEAD 构建发布上去（与基线 269 个成员中其余 268 个早已逐字节一致），重启后执行器重新注册并声明 `video_generate:1.0.0` |

漂移的实际影响有限（这 4 个值在生产由 image-extension 的 `executor-extension.properties` 提供，
而该配置片段在 DEF-12 的修复中被显式保留），但"生产 == 已提交代码"这一不变式现在成立了，
不再依赖"某个配置片段恰好补上"。

## 10. App 侧的验证边界

App 只做了**编译与静态检查**，没有真机/模拟器验证：系统图片选择器、`Video` 组件播放、
大文件上传与端到端联调都没有实跑（服务端尚未部署）。这是本轮最大的验证缺口，
部署后需要一次真机走查。

App 为接入视频改动了两处**共享网络代码**（`AuthorizedApiClient` 新增 `postBinary`/`downloadVideoToUri`、
`DownloadStreamIntegrityPolicy` 新增 `video/*` 前缀分支），既有方法签名与行为未变，
相关静态检查通过。该文件里另有他人未提交的改动（`delete` 的取消参数、`usingCache`），不属于本次工作。

## 11. 已知限制与未做的事

- **没有人工评分**：P0 的机器复核结论仍然生效，`reviews.json` 全部是 `pending`；
  P1 没有改变这一点。
- **`MASKED_EDIT` 的 M01 冲突未解决**：分区指标说局部性成立（外 0.0113 / 内 0.1136），
  VLM 说背景变了。该模式默认关闭。
- **视频下载不做 Range 分片**：成片约 1–3MB，App 直接整段下载到缓存再播放；
  网关读取超时沿用 15s。若将来支持更长片段需要改成流式/分片。
- **释放显存依赖执行器正常退出**：租约在 `__exit__` 里清空视频权重后再放锁；
  进程被 SIGKILL 时权重会残留，下一个持锁者靠 `VIDEO_MIN_FREE_MIB`（14848 MiB）自保并报
  `GPU_MEMORY_UNAVAILABLE`，不会 OOM。图片包侧存在同样的已知风险（P0 已记录）。
- **视频运行时长期驻留**：空闲时权重已卸载（约 300MiB 显存），与图片 Comfy 并存；
  两个进程的权重加载由同一把 flock 串行化。
- 未做：多用户并发排队的公平性验证、成片清理/配额策略、`works` 分页的深翻页性能。
