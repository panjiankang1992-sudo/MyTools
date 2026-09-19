# Image generation service

MyApp「工具 → 图片生成」的独立业务服务。默认关闭生成能力；安装应用或部署服务本身不会下载模型、占用 GPU 或发出付费图片请求。

## 已实现的链路

- App：文字输入、可选底稿（每张不超过 1 MB，最多由模型能力控制）、模型选择、尺寸/数量、种子、异步作品列表、取消、保存、参数复用、作品转底稿。
- Gateway：`/api/app/v1/image-generation`，从登录会话生成所有者；客户端传入的 `X-Owner-Id` 不会转发。
- 图片服务：MySQL 持久队列、用户隔离、请求幂等、重启对账、结果完整解码、部分成功保留、Asset Registry 幂等登记。
- Scheduler：`image_generate`，单并发专属集群、禁止自动重试提交，只接受 `image-generation-service` 的独立令牌。
- Executor：不可变包 `image_generate/1.0.0`。本地使用经过摘要校验的 ComfyUI API 工作流；远端预置 OpenAI Images 协议适配器，拒绝重定向及远端图片 URL。
- 标签包 `media_generate_tags/1.4.0`：沿用 1.3.0 的人物优先提示词，增加可关闭的 GPU 租约。旧版本文件不修改。

Krea 所需的 Qwen3VL-4B 是独立文本编码器权重，不替换或重新安装已删除的 Ollama 4B 标签模型；打标签仍使用现有 8B 模型。

图片与标签包共用 `gpu_lease.py`：同一文件锁串行调用；切换到图片前仅卸载受管 Qwen 标签模型；切换到标签前释放 Comfy 权重，并通过 `nvidia-smi` 等待显存实际释放。遇到其他驻留模型即拒绝启动；取消后的 Comfy 队列最多等待 60 秒排空，再释放权重和检查显存。

## 当前边界

- 已完成 Ubuntu 生产部署及真实 App → Scheduler → 模型 → Asset Registry → 历史图片链路验收，参见 [上线记录](../../docs/verification/2026-09-13-image-generation/production-release.md)。此前隔离实例的 20 张测试见 [验证报告](../../docs/verification/2026-09-13-image-generation/report.md)。仓库默认开关仍关闭，生产通过独立私密配置启用本地文生图。
- `workflows/krea2-turbo-v1.candidate.json` 已从官方工作流整理出 NVFP4 文生图候选（关闭扩写与 LoRA、编码器放 CPU），并固定 Comfy commit；`krea2-models.lock.json` 固定官方权重 revision、大小和 SHA-256。候选已完成上述独立实例实图验证；文件中的 validated 仍为 false，生产能力开关须等完整链路验收后启用。
- `https://api.sillytraven.dev/api/ai/v1/` 前一轮探测为 Cloudflare 403；本次没有继续绕过或重试。远端协议适配器尚未在该供应方获得实图验证，只开放文字生成，未声称它支持图片编辑。
- 风格参考需独立验收；当前适配器以受管本机文件作为工作流输入，要求安装并验证对应输入节点。1.1.0 新增独立图生图和图片反推提示词路径，启用条件见下节；未实现任意区域重绘。
- 图片服务部署为单实例；Executor 与图片服务必须共享同一持久目录。多实例对账、对象存储及独立远程 GPU 主机不在本次范围内。
- 本机任务取消会请求取消当前 Comfy prompt；若进程被强杀导致请求未完成，后续 GPU 租约会等待残留队列排空，超时拒绝启动。不会停止不属于当前 prompt 的任务。

## 接口

所有外部接口均需用户 Bearer。内部接口 `/internal/v1/images` 只接受图片服务令牌及可信所有者。

| 方法 | 外部路径后缀 | 用途 |
|---|---|---|
| GET | `/models` | 能力与 READY / UNVERIFIED 状态 |
| POST | `/uploads` | `{base64}`；服务端上限 5 MB，完整检测 PNG/JPEG |
| POST | `/jobs` | 幂等提交生成任务 |
| GET | `/jobs/{id}` | 本人任务状态 |
| POST | `/jobs/{id}/cancel` | 持久化取消请求 |
| GET | `/works?page=0` | 每页 20 条本人历史 |
| GET | `/uploads/{id}` | 本人底稿 |
| GET | `/jobs/{id}/images/{index}` | 已登记的本人图片 |
| POST | `/jobs/{id}/images/{index}/reference` | 本人成品复制为新底稿 |

创建示例（不包含端点或密钥）：

```json
{"resourceId":"krea2-local","prompt":"A photographer in warm cinematic light","mode":"TEXT_TO_IMAGE","references":[],"size":"1024x1024","count":1,"seed":42,"idempotencyKey":"client-generated-unique-key"}
```

相同所有者、相同幂等键和相同载荷返回原任务；更改载荷返回 409。供应方未验收返回 503。状态为 `QUEUED → RUNNING → PERSISTING → SUCCEEDED / PARTIAL_SUCCESS / FAILED`，取消经 `CANCEL_REQUESTED → CANCELLED` 对账。取消前已原子落盘的图片仍会登记为部分结果。

## 部署与启用顺序

使用项目配置的 SSH 连接 Ubuntu，先验证连通性。沿用现有 release/systemd 流程，不重写生产环境文件，不输出任何凭据。

1. 打包新服务、Gateway、Scheduler、Executor，并通过 `assemble_executor_packages.py` 组装**全部**不可变包，包含历史版本、图片 1.0.0 和标签 1.4.0。V150 默认关闭图片集群及任务；V151 将标签定义升至 1.4.0，必须先让执行节点具备该发布包。历史运行任务继续使用原快照。
2. `services.json` 已登记端口 23340、数据库 `mytools_image_generation`、默认不随主 target 启动。通过现有建库工具创建专属最小权限账户。显式启动图片服务。
3. 私密配置独立 `IMAGE_GENERATION_INTERNAL_TOKEN`（Gateway 与图片服务）及 `TASK_BUSINESS_IMAGE_GENERATION_TOKEN`（图片服务与 Scheduler）。资产调用使用已有 `ASSET_REGISTRY_INTERNAL_TOKEN`。新环境生成器会生成这些令牌；现有环境只增量补齐，不重新生成其他服务令牌。
4. 设置双方相同的 `IMAGE_GENERATION_ROOT`，如 `/opt/yuyutian/mytools/runtime/image-generation`，创建 `inputs/`、`outputs/`，由 `mytools` 持有。设置共享 `IMAGE_GPU_LOCK_FILE=/opt/yuyutian/mytools/runtime/gpu.lock`；不能放在 systemd `PrivateTmp` 或版本化 release 目录。
5. 开启 `IMAGE_GENERATION_ROUTE_ENABLED=true` 仅开放业务界面和模型目录；本地、风格与远端生成门禁继续保持 false。通过两账户验证列表和下载隔离。
6. 使用锁定清单与候选工作流安装固定版本的本机 ComfyUI 与 Krea 2 Turbo，优先按方案验证 NVFP4 单图约 1 MP；CPU offload/FP8 是另一个需要分别测量的配置。保持 batch=1、8 steps，四张请求串行。模型文件放持久磁盘，不使用 `/tmp` tmpfs。
7. 导出实际 API graph，建立下节工作流清单，校验节点均为本机计算、无云端 partner node、无动态代码。配置 `IMAGE_WORKFLOW_FILE` 与 `IMAGE_WORKFLOW_SHA256`（完整文件 SHA-256），以及 `IMAGE_COMFY_URL`。图片服务 `IMAGE_GENERATION_WORKFLOW_REVISION` 与文件 revision 一致。
8. 验证所有使用这张 GPU 的标签执行节点已切换 1.4.0，并使用同一个锁文件；停止或纳入协调其他 GPU 调用者。在图片服务与相关 Executor 同时配置 `IMAGE_GPU_COORDINATION_VALIDATED=true`，再进行受控推理验收。两个服务的 `IMAGE_GENERATION_LOCAL_VALIDATED=true` 也必须一致。
9. 通过 Scheduler 管理 API 显式启用 `image-generation` 集群与 `image_generate` 定义，单并发；将受管 Executor 加入该集群并增量加入注册允许列表 `TASK_ALLOWED_EXECUTOR_CLUSTERS`，保留其他原有集群。不要直接改调度数据库。
10. 验收从 App 创建到 Scheduler、ComfyUI、落盘、Asset Registry、App 下载的完整链路。覆盖冷启动、1/4 张、标签交替、取消、服务重启、资产服务故障恢复、最大显存/RAM/Swap、延迟。GPU 锁等待上限 120 秒，每张图片后释放；单图推理必须落在现有标签步骤超时预算内，否则需先完成调度预算调整再开启共存。性能数据必须来自实测；失败立即关闭本地生成门禁，保留历史作品可读。

配置名以 `src/main/resources/application.yml` 与 Executor `script-environments.image_generate` 为准。标签 GPU 门禁关闭时维持原有标签调用，不依赖 ComfyUI。

### 工作流清单格式

下例只说明绑定协议，**不是可运行的 Krea graph**。`graph`、`allowedClassTypes`、`outputNode` 和绑定节点 ID 必须来自实际验收版本；不要将示例设置为已验收。

```json
{
  "revision": "krea2-turbo-v1",
  "modes": ["TEXT_TO_IMAGE"],
  "allowedClassTypes": ["actual-installed-node-class"],
  "graph": {},
  "bindings": {
    "prompt": [["actual-node-id", "text"]],
    "width": [["actual-latent-id", "width"]],
    "height": [["actual-latent-id", "height"]],
    "seed": [["actual-sampler-id", "noise_seed"]]
  },
  "outputNode": "actual-save-image-id"
}
```

绑定值直接写入指定节点输入，不进行字符串模板执行。风格工作流还需要 `reference0`、`reference1` 的受管文件读取输入绑定，并将 `STYLE_REFERENCE` 加入 modes；分别验收 1 张与 2 张底稿后再开启 `IMAGE_GENERATION_STYLE_VALIDATED`。输出节点每次必须只返回一张 PNG。

### 远端启用

只有供应方实测成功后，配置 `IMAGE_REMOTE_MODEL` 为其真实可用模型，`IMAGE_REMOTE_KEY_FILE` 指向当前加料资源使用的受管密钥文件，`IMAGE_REMOTE_URL` 指向已验收的基址，再在服务与 Executor 同时开启 `IMAGE_REMOTE_VALIDATED`。不复制密钥到 App、任务上下文或仓库。当前只支持 `POST /images/generations` 同步返回单张 `b64_json` PNG；不支持该协议时需实现对应适配器，不能靠改模型名宣告支持。

请求开始前持久写入提交标记，超时和响应丢失不自动付费重试。本地与远端切换由用户明确选择；没有隐式 fallback。远端失败可能已经计费时，由运维通过供应方记录对账。无法确认提交结果且没有图片时返回 `UNCONFIRMED`；有部分图片时保留 `PARTIAL_SUCCESS` 并附带 `unconfirmed=true`，App 提示先核对结果。

## 验证命令

```sh
mvn -f service/image-generation-service/pom.xml test
mvn -f service/mytools-gateway/pom.xml -Dtest=ImageGenerationGatewayControllerTest,AppCatalogGatewayClientTest test
mvn -f service/task-scheduler-service/pom.xml -Dtest=ImageTaskAccessTest test
python3 -m unittest discover -s service/image-generation-service/packages/image_generate/1.0.0/tests -v
python3 -m unittest discover -s service/media-intelligence/packages/media_generate_tags/1.4.0/tests -v
python3 -m unittest discover -s service/deploy -p 'test_*.py'
python3 service/scripts/assemble_executor_packages.py --output /tmp/mytools-image-packages
```

这些测试使用 H2、模拟内部 HTTP/Comfy 响应及文件系统；不等同于 Krea 真机推理或 Ubuntu 上线验收。HarmonyOS 应用需另行执行 `assembleHap`。

### 本次固定版本发布工具

`service/deploy/deploy_image_generation.py` 为 `image-production-20260913-v1` 的有界发布工具，支持 `stage`、`activate`、`enable` 和 `rollback`。在目标 Ubuntu 上使用已有 release venv，所有实际切换须通过 `sudo flock -n /opt/yuyutian/mytools/runtime/deployment.lock` 串行执行。工具使用独立 JAR 路径与 `zz-image-generation.conf`，不会切换 `releases/current` 或修改章节改编配置。

Scheduler 新增 `POST /internal/v1/image-generation-deployment`，请求体为 `{"enabled":true,"auditId":"release-audit-id"}`，只接受独立 `task-operator-service` 凭据。V152 保存开关审计记录。回退先关闭图片任务并排空执行，保留图片数据与标签 1.4.0 包，不回滚数据库版本。

`verify_image_generation.py` 使用专用验收所有者隔离验证业务服务 → Scheduler → 模型 → Asset Registry → 历史下载，并检查跨所有者读取拒绝、重复提交复用及取消。App 登录入口另做真实页面验收。

## 图生图与图片反推提示词（1.1.2）

2026-09-14 已在 Ubuntu 发布 `image-extension-20260914-v3`，真实图生图、视觉反推、反推结果再生成以及隔离/幂等/取消/重启恢复均通过。详见 [验收记录](../../docs/verification/2026-09-14-image-extension/report.md)。历史包保持不变；V153/V154 将新任务切换至 1.1.2，已有任务使用原快照。1.1.2 使用结构化 JSON 输出；兼容受管模型将 JSON 放在 content 或 thinking 字段的行为，严格提取 prompt，拒绝普通思考文本、附加字段与截断结果。

- 图生图：选择具备 `IMAGE_TO_IMAGE` 能力的本地模型，上传一张 PNG/JPEG 并填写修改描述。任务同时传递 `references[0]` 和 `prompt`，支持原有尺寸、数量、种子、取消、历史及资产登记。
- 反推提示词：选择“图片反推提示词”，上传一张图片，无需输入文字。通过原有异步任务接口执行，完成后 `result.prompt` 返回可复制的描述；App 中点击“用于创作”回填到文生图。该描述是视觉推测，不是恢复原始提示词。
- 两种模式均只接受当前所有者上传的 UUID，不接受外部 URL、文件路径、模型凭据或工作流节点。每次只能传一张底稿。App 上限 1 MB，后端上限 5 MB，沿用 PNG/JPEG 完整解码验证。
- 反推复用 `TAGGING_MODEL` 与 `TAGGING_SERVICE_URL` 的 Ollama `/api/chat` 协议，共用标签 GPU 租约，使用 `keep_alive=0`；结果以 `prompt.json` 原子落盘，重试读取已有结果，不登记图片资产。现有网关路径和认证方式无需改变。

图生图任务示例：

```json
{"resourceId":"krea2-local","prompt":"Keep the subject and composition, change the lighting to sunset","mode":"IMAGE_TO_IMAGE","references":["<uploaded-uuid>"],"size":"1024x1024","count":1,"seed":42,"idempotencyKey":"unique-edit-request"}
```

反推任务示例（`prompt` 为兼容现有任务结构的占位说明，视觉描述指令由服务端执行包固定；`size`、`seed` 不控制视觉分析）：

```json
{"resourceId":"vision-local","prompt":"Describe the image as a generation prompt.","mode":"IMAGE_TO_PROMPT","references":["<uploaded-uuid>"],"size":"1024x1024","count":1,"seed":0,"idempotencyKey":"unique-prompt-request"}
```

新增配置：

| 配置 | 使用位置 | 说明 |
|---|---|---|
| `IMAGE_GENERATION_EDIT_VALIDATED` | 图片服务、Executor | 默认 false；独立图生图工作流实测后开启 |
| `IMAGE_EDIT_WORKFLOW_FILE` / `IMAGE_EDIT_WORKFLOW_SHA256` | Executor | 图生图工作流清单文件及完整 SHA-256，不复用文生图文件 |
| `IMAGE_EDIT_WORKFLOW_REVISION` | 图片服务 | 与上述清单 revision 一致，默认 image-edit-v1 |
| `IMAGE_PROMPT_VALIDATED` | 图片服务、Executor | 默认 false；视觉反推实测后开启 |
| `TAGGING_MODEL` | 图片服务、Executor | 双方必须一致，沿用受管视觉模型 |

图生图清单必须包含 `IMAGE_TO_IMAGE` 模式以及 `prompt`、`width`、`height`、`seed`、`reference0` 绑定。图生图 `reference0` 是经本机 `/upload/image` 上传后校验的 Comfy 输入目录相对路径，必须绑定到经验证的 LoadImage 节点，并确保其输出进入实际生成的图像条件/潜空间链路。不能只修改清单 modes 或把它接到未使用节点就宣告支持。新增 `workflows/krea2-image-edit-v1.candidate.json` 候选，以 LoadImage → ImageScale（居中裁剪）→ VAEEncode → KSampler 接入底稿，固定候选 denoise=0.65，沿用锁定权重。节点接口依据[固定版本 ComfyUI 源码](https://github.com/Comfy-Org/ComfyUI/blob/02d39c8cd7828566f48ccf783c1c75b8336044f5/nodes.py)核对；适配器通过本机上传接口传入底稿，遵守线上 Comfy 的输入目录隔离，仍需完成质量与显存验收。候选 `validated=false`，不能直接开启新门禁。图生图仍要求本地生成与 GPU 协调原有门禁开启，反推只要求视觉验收与 GPU 协调开启。

本地验证：`mvn -f service/image-generation-service/pom.xml test`、`python3 -m unittest discover -s service/image-generation-service/packages/image_generate/1.1.2/tests -v`，以及 App `assembleHap`。模拟测试覆盖输入与提示词绑定、所有者隔离、独立能力门禁、文本落盘/复用、空结果拒绝及旧图片生成回归；不替代真机质量、显存与取消验收。

## 文字风格与账户私有风格

首版提供 24 个内置文字模板、私有风格 CRUD 和不可变任务快照。新增图片库 Flyway V2，Gateway 与 App 需同步发布；执行包 1.1.2 保持原样，派发时由图片服务把最终提示词投影到现有契约。

功能、操作方法、接口、测试与回退限制见 [图片风格实现说明](../../docs/design/2026-09-14-image-style-implementation.md)。已通过 image-styles-20260914-v1 发布，并完成代表性端到端验收；LoRA 和独立风格参考不在本版范围。
