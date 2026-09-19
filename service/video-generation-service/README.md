# video-generation-service

本机视频生成工具的服务端。用户素材先落到受管目录，任务以幂等键写入自己的库，
再经调度器派发给 `video_generate/1.0.1` 执行器，在共享显存租约内跑固定 VACE 工作流。
成片与封面登记到资产服务，App 只通过网关访问。

## 为什么独立成服务

- 视频推理吃满显存（49 帧实测峰值约 14.5 GiB），必须和图片、标签任务共用同一把 GPU 锁；
  锁协议只在任务执行器侧生效，业务服务不能直接持有 GPU。
- 客户端不能提交 ComfyUI 节点图、采样参数或上游地址：工作流是部署期固定的不可变规格。
- 未通过验收的模式一律不对外提供，但会出现在能力目录里并带原因码，避免"看起来支持"。

## 验收边界（与 P0 报告一致）

`docs/verification/2026-09-14-video-generation/report.md` 给出了逐模式结论。服务只把
**显式打开验收开关**的模式标记为可用：

| 模式 | 环境开关 | P0 结论 |
|---|---|---|
| `FIRST_FRAME`（单图首帧，控制强度 α=0.25） | `VIDEO_FIRST_FRAME_VALIDATED` | 可用（机器复核）；补边已由白改中性灰，需下一次维护窗口复验 |
| `TEXT_TO_VIDEO` | `VIDEO_T2V_VALIDATED` | 有限：指令遵循未验证 |
| `SUBJECT_REFERENCES`（恰好两张） | `VIDEO_REFERENCES_VALIDATED` | 有限：参考控制未验证；三图参考判为不可用，服务端只收两张 |
| `FIRST_LAST_FRAMES` | `VIDEO_FIRST_LAST_VALIDATED` | 有限：首末帧差异偏小 |
| `STRUCTURE_RESTYLE` | `VIDEO_RESTYLE_VALIDATED` | 有限：暗低纹理源片判为不可用，执行器会直接拒绝此类素材 |
| `MASKED_EDIT` | `VIDEO_MASKED_VALIDATED` | 不可用：局部性存在指标与模型复核冲突，未解决 |

同时要求 `VIDEO_LOCAL_VALIDATED` 与 `VIDEO_GPU_COORDINATION_VALIDATED` 都为 `true`；
只开模式开关不会让任何模式上线。

帧数固定 **49**、帧率固定 **16**、尺寸固定 **832×480**（竖屏尚未验证）。原声策略目前只接受
`SILENT`；`KEEP_SOURCE_AUDIO` 会返回 `VIDEO_002`，而不是静默产出无声视频。

## 接口

内部接口前缀 `/internal/v1/videos`，只接受网关转发的 `Authorization: Bearer <token>` 与
`X-Owner-Id`；所有者永远由网关从已认证主体派生，客户端不能指定。

| 方法与路径 | 说明 |
|---|---|
| `GET /models` | 能力目录（含未验收模式与原因码） |
| `POST /uploads/image` | 原始字节上传图片素材（≤20MB，需可解码，≤16MP） |
| `POST /uploads/video` | 原始字节上传视频素材（≤200MB，≤60 秒） |
| `POST /jobs` | 幂等创建任务（`idempotencyKey` 必填） |
| `GET /jobs/{id}` | 查询任务与阶段事件 |
| `POST /jobs/{id}/cancel` | 请求取消（排队中直接取消，否则转 `CANCEL_REQUESTED`） |
| `GET /works?page=` | 本人作品历史 |
| `GET /uploads/{id}` | 读取本人素材 |
| `GET /jobs/{id}/video` | 读取本人成片（`video/mp4`）；支持 `Range`，命中回 206 并带 `Content-Range`，越界回 416 |
| `GET /jobs/{id}/cover` | 下载本人成片封面（PNG） |

上传素材的 `GET /uploads/{id}` 同样支持 `Range`（原片对比时可以只取窗口）。读取接口一律只在
请求窗口内读盘，不为一次播放把整段（素材上限 200MB）读进堆。

原生播放器（AVPlayer / ArkUI `Video`）无法附加登录头，所以播放走网关签发的**短期票据**：
`POST /api/app/v1/video-generation/jobs/{id}/ticket`（原片用 `uploads/{id}/ticket`）返回
两小时票据与相对地址，播放器再取 `/api/app/v1/video-generation/tickets/<32 位十六进制>`；
票据只绑定签发者的那一个资源。方案文档写作 `/api/video-generation/**` 的前缀是同一批处理器的别名。

错误码：`VIDEO_001` 输入无效、`VIDEO_002` 能力不支持、`VIDEO_003` 资源不足、
`VIDEO_004` 推理失败、`VIDEO_005` 超时、`VIDEO_006` 资产保存失败、`VIDEO_007` 鉴权失败、
`VIDEO_008` 幂等冲突、`VIDEO_009` 资源不存在。

## 数据与文件

- 库：`mytools_video_generation`，迁移在 `db/migrations/`（Flyway）。
- 文件：`VIDEO_GENERATION_ROOT` 下的 `uploads/`（素材）、`outputs/<jobId>/`（成片与封面）、
  `work/<jobId>/`（预处理中间产物）。
- 任务参数快照里**不写主机绝对路径**，执行器按根目录与 `uploadId` 自行定位。

## 补边还原（1.0.1）

参考图/源片按目标尺寸等比缩放后由 ffmpeg `pad` 补中性灰边（`0x808080`）。但模型会把成片里这两条边
改成任意颜色（实测同一素材一次偏蓝一次偏黄），所以 1.0.1 起在出片阶段按**控制帧量出的几何**把补边
覆盖回中性灰：

- `vace_control.detect_pad_rects(control_frame)` 量出 `(left, right, top, bottom)`：补边是整列/整行
  同一个中性灰，扫描边界即可；对边互不重叠，整幅纯色也不会把有效画面吞掉。
- `vace_control.restore_filter(rects, width, height)` 生成等价的 `drawbox` 滤镜，**成片与封面共用**，
  避免封面留着被改色的边；结果里的 `padRects` 记录本次几何，便于验收复核。
- 几何来自控制帧而不是重新推导 ffmpeg 的取整细节，因此与工作流实际喂进去的输入严格一致。
- 与开发期工具 `tools/prepare_control.py` 同源，`tests/test_worker.py` 的漂移用例逐项比对两者。

回归用例见 `packages/video_generate/1.0.1/tests/test_end_to_end.py`：假 Comfy 故意把两侧涂成蓝色，
断言成片第 0/24 帧与封面的补边仍是中性灰、而中间区域未被覆盖。

## 关键配置

| 变量 | 说明 |
|---|---|
| `VIDEO_GENERATION_PORT` | 监听端口，默认 23341 |
| `VIDEO_GENERATION_INTERNAL_TOKEN` | 网关到本服务的内部令牌 |
| `VIDEO_GENERATION_ROOT` | 受管根目录 |
| `VIDEO_GPU_LOCK_FILE` | 与图片、标签包共用的显存锁文件（执行器侧） |
| `VIDEO_COMFY_URL` / `VIDEO_COMFY_OUTPUT_DIR` | 视频 Comfy 运行时（默认 127.0.0.1:8190） |
| `VIDEO_MIN_FREE_MIB` | 进入推理前要求的可用显存（默认 14848 MiB，49 帧实测峰值约 14.5 GiB） |
| `VIDEO_INFERENCE_TIMEOUT_SECONDS` | 单次推理上限（默认 2400 s；实测 8–17 分钟） |
| `VIDEO_WORKFLOW_INDEX_FILE` / `VIDEO_WORKFLOW_INDEX_SHA256` | 固定工作流规格索引与摘要（执行器侧） |
| `TASK_BUSINESS_VIDEO_GENERATION_TOKEN` | 调度器业务令牌 |
| `ASSET_REGISTRY_INTERNAL_TOKEN` | 资产登记令牌 |

## 构建与验证

```bash
mvn -o -q -f service/video-generation-service/pom.xml compile
mvn -o -f service/video-generation-service/pom.xml test
```

本机可跑、不需要 GPU 的执行器测试：

```bash
cd service/video-generation-service/packages/video_generate/1.0.1/tests
python3 -m unittest test_worker
```

工作流规格由已验证的构图代码生成（改构图必然改哈希）：

```bash
python3 service/video-generation-service/tools/emit_workflow_specs.py --output-dir <目录>
```
