# P0 验证执行手册

此手册用于下一阶段实施。**2026-09-14 已执行 A、B、C 节的环境准备、权重下载与六个固定工作流的受控推理**
（文生 49/81 帧、单图首帧、两图与三图主体参考、首尾帧、灰度控制重绘、静态蒙版局部修改、
灰度与边缘控制对比，以及分镜 3 镜串行生成与拼接，共 15 次推理、1 次作废），
结果与原始证据见 [P0 验证报告](../../verification/2026-09-14-video-generation/report.md)；
D 节原生 Python 路径**尚未执行**。统一 GPU 准入仍通过调度层暂停派发实现，推理命令没有加入生产任务包。

**窗口必须放进独立 systemd 瞬态单元运行，不能作为 SSH 会话的子进程**：后者在连接中断时会被
SIGHUP 杀死，`finally` 中的恢复逻辑不会执行，生产调度会一直停在暂停状态（已实际发生过一次，
中断约 10 分钟）。用 `p0_trial.py run <workflows>` 启动，恢复命令是 `p0_trial.py restore`。

按 acceptance.md 要求的两个固定种子（42、932）复跑是必要的、不是形式要求：**单图首帧在 seed 932 下
出现 2 帧真全黑 + 7 帧极暗**（已用 ffprobe signalstats 独立复核），只跑一个种子会完全漏掉这个严重缺陷。
后续任何模式的"可用"结论都必须建立在两个种子都通过的前提上。

实测得到的两个启动参数结论：`--lowvram` 在默认启用 DynamicVRAM 的版本上是空操作；
`--reserve-vram 2` 也不是硬约束——81 帧运行时峰值显存 15436MiB，16GB 卡只剩 875MiB 余量。
**832×480 / 81 帧已接近本机实际上限。**

## A. 运行时准备

1. 再运行 host probe，确认 GPU 无未知任务、内存余量达到主方案门槛。
2. 在 Scheduler 暂停本应用新的 GPU 派发，等待图片/标签排空；不能取消其他用户任务来抢资源。
   暂停范围必须按 `task_step_definition.script_package` 反查**全部**含 GPU 步骤的任务定义（图片 1 个 +
   标签 7 个），不能只限制图片任务：标签推理嵌在下载任务里，长下载任务可能在窗口中途才走到标签步骤。
3. 获得同一个 GPU 租约，回收已知空闲推理缓存，重新测量 MemAvailable。只有得到足够余量才启动视频实验；结束后恢复图片服务并重测。协调器完成前，以上操作在明确维护窗口进行。
4. 新建受限运行目录与独立环境，建议复用已验证的 Comfy 源码 revision，使用独立 venv；不能 `pip install` 到图片生产 venv。

### 实测修订（2026-09-14）

- **方案原定的 20GiB 启动门槛在本机不可达**：停 image Comfy 后 `MemAvailable` 只有约 18.5GiB。原因
  是 image Comfy 的 cgroup 数字（约 10.6GiB）几乎全是可回收页缓存，已计入 `MemAvailable`，停它只释放
  约 0.6GiB；真正的占用来自生产 Java/Python 栈的约 10.3GiB 匿名内存。
- **准入门槛按实测校准为 18GiB，worker `MemoryHigh=10G`、`MemoryMax=12G`、`MemorySwapMax=0`**，
  即 `MemoryMax = 启动门槛 − 6GiB 主机保留`，保持"运行中主机保留 ≥6GiB"不变式；超限只由视频单元自身
  承担 OOM。实测四轮推理 worker 峰值 9.18GiB、主机可用内存从未低于 14682MiB，PSI ≤0.02。
- **`--lowvram` 在本版本是空操作**：固定版本 ComfyUI 默认启用 DynamicVRAM，`comfy/cli_args.py` 明确
  说明 `--lowvram` 在启用 dynamic vram 时不生效。显存分配改由 DynamicVRAM 自适应；实测峰值显存
  14540MiB（`--reserve-vram 2` 生效），worker 峰值 9.18GiB。**不要再用"`--lowvram` 控制显存"作为论据。**
- 需要多图主体参考时，必须在启动参数中显式放行本仓库自带的派生节点，其余自定义节点继续关闭。

建议布局：

```text
/opt/yuyutian/mytools/runtime/video-generation/
  runtime-v1/ComfyUI/
  runtime-v1/venv/
  model-download/
  models/
  workflows/
  input/
  output/
  evidence/
```

独立环境初始使用与现网相同的 Torch/CUDA 版本作为候选；先验证 CUDA 小张量运算、VAE 编解码和节点导入，再固定 pip freeze 与 wheel 哈希。现网 Python/torch 能生成图片不是视频验证证据。不要照搬 VACE 旧 README 的 cu124 栈到 Blackwell。

固定源码候选：ComfyUI `02d39c8cd7828566f48ccf783c1c75b8336044f5`。若所需节点在此版本不可用，只升级隔离环境并记录新 commit。

建议启动参数（完成目录/venv/租约之后）：

```bash
export task_video_root=/opt/yuyutian/mytools/runtime/video-generation
cd "$task_video_root/runtime-v1/ComfyUI"
"$task_video_root/runtime-v1/venv/bin/python" main.py \
  --listen 127.0.0.1 --port 8190 --lowvram \
  --reserve-vram 2 --disable-pinned-memory \
  --disable-all-custom-nodes --whitelist-custom-nodes wan_vace_multi_reference \
  --disable-api-nodes --preview-method none
```

以上使用 systemd 限制内存并由协调器启动，不长期与图片运行时双驻留。初期优先原生节点，使用分块 VAE；CPU VAE 作为另外一组低显存测试。缺少必须的控制预处理节点时，将它放在独立、固定版本的预处理进程，禁止自动安装任意 Manager 插件。

`--lowvram` 在默认启用 DynamicVRAM 的版本上不生效（见上），保留它只是与方案参数一致；
显存与内存占用由 DynamicVRAM 与 cgroup 上限共同约束。`--whitelist-custom-nodes` 只放行本仓库自带的
多图参考派生节点，其文件哈希必须记录进窗口证据。

## B. 首期下载清单

从 Comfy 官方仓库读取的精确清单见 [comfy-models-lock.json](comfy-models-lock.json)，包含 SHA-256。官方教程的粗略大小与当前元数据不完全一致，以锁定文件字节数为准。

| 文件 | 大小（十进制 GB） | 用途 |
|---|---:|---|
| wan2.1_vace_1.3B_fp16.safetensors | 4.310 | 三类输入基线 |
| umt5_xxl_fp8_e4m3fn_scaled.safetensors | 6.736 | 文本编码 |
| wan_2.1_vae.safetensors | 0.254 | 视频编码/解码 |
| wan2.1_t2v_1.3B_bf16.safetensors | 2.838 | 可选文生对照 |

前三项约 11.30GB；四项共约 14.14GB。另留环境、下载缓存和输出空间；这些不是显存峰值。

在已准备的独立 Python 环境安装 HF CLI 后，只下载所需文件：

```bash
hf download Comfy-Org/Wan_2.1_ComfyUI_repackaged \
  split_files/diffusion_models/wan2.1_vace_1.3B_fp16.safetensors \
  split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors \
  split_files/vae/wan_2.1_vae.safetensors \
  --revision 617a7633e636506f850e043bc4605f290a466a8e \
  --local-dir "$task_video_root/model-download"
```

下载后校验字节数与 SHA-256，将对应文件部署到隔离 Comfy 的 models 子目录；模型目录只读挂载给推理进程。不要下载整个仓库（包含大量无关大模型），不要把 Wan 原生 `.pth` 直接当 Comfy 分拆 safetensors。

## C. 六个固定工作流

从 [Comfy 官方 VACE 教程](https://docs.comfy.org/tutorials/video/wan/vace) 对应模板开始，导出 **API 格式**，不要把 UI 的节点/连线 JSON 直接 POST 到 `/prompt`。官方页面提示模板和文档可能存在更新差异，节点 schema 必须以固定运行时 `/object_info` 验证。

| 工作流 ID | 核心连接 | 必须验证 |
|---|---|---|
| vace-t2v-v1 | T5 正/负描述 → WanVaceToVideo → 采样 → VAE → 视频编码 | 无图片输入；模型明确替换成 1.3B |
| vace-r2v-v1 | 上述＋reference_images | 1/2/3 图均参与，不能只使用第一张 |
| vace-firstframe-v1 | 第 0 帧是原图，其余帧未知；对应掩码 | 与主体参考是两条不同图，不混用 |
| vace-firstlast-v1 | 第 0/N−1 帧分别为原图，其余未知 | 最后一帧位置不能按 FPS 误算 |
| vace-control-v1 | 标准化源片 → 灰度/边缘或深度 → control_video | 来源视频不是直接当深度图传入 |
| vace-mask-v1 | 标准化源片＋同尺寸同时间掩码 → 编辑条件 | 白色生成、黑色保留，检查反转错误 |

统一初值：832×480、49 帧、batch 1、seed 42；50 步、shift 16、CFG 5。用 81 帧验收后才开放“约 5 秒”。蒙版预处理另测灰边/插值，禁止把模糊缩放当二值掩码。

**2026-09-14 实测状态**：六类工作流都已产出真实产物。"控制信号"与"掩码"两类的控制预处理由
`tools/prepare_control.py` 生成并记录哈希：掩码语义按官方 `frameref` 核对（参考帧 0 保留、
其余 255 生成），灰度按官方 `gray.py` 的 BGR2GRAY 亮度实现。局部修改先做**静态矩形蒙版**并做了
分区客观检查（掩码外差异 0.0113、掩码内 0.1136），**移动区域的跟踪蒙版仍未实现**。
"控制信号"只跑了灰度分支，边缘分支与深度控制未运行。

多图适配必须查看 `WanVaceToVideo` 在固定版本对 reference_images 的编码方式。逐图输入、拼图或批次不是等价操作；如果基础节点无法表达所需独立参考关系，改用官方 WanVace Python prepare_source 路径，保持各图身份，并给该工作流单独版本。

**已在固定版本确认**：`comfy_extras/nodes_wan.py` 使用 `reference_image[:1]`，原生节点只取第一张图，
第二张起被静默丢弃。因此多图主体参考使用本仓库自带的派生节点
（`service/video-generation-service/custom_nodes/wan_vace_multi_reference/`），并把参考图编码改为
逐张编码为独立参考潜帧后沿时间维拼接；`trim_latent` 与 latent 长度增量都取自实际参考帧数。
该工作流单独版本，参考图数量限定 2–3 张，且必须做"第二张图确实影响结果"的对照实验，
不能仅凭"接口收下多张图"或"推理成功"认定支持多图。

每个 API 工作流固定模型、采样器及节点类型，只允许注入 prompt、输入文件、seed、白名单尺寸与帧数；客户端不能提交任意节点图。保存模板 SHA-256 和节点 schema 哈希。

## D. 原生 Python 对照命令

需要隔离的 Wan2.1 原生环境和相应**原生权重**；与 B 节 Comfy 权重二选一按需下载。这些参数已对照当前官方 `generate.py`，但依赖/内核与推理仍未本机测试。

源码固定到 `9737cba9c1c3c4d04b33fcad41c111989865d315`。环境需要检查 FlashAttention 的实际调用路径；不能因为存在一个 SDPA fallback 函数，就认为所有模型调用都能无 FlashAttention 运行。若不兼容，先用已验证的 Comfy 路径，不改生产 torch。

```bash
hf download Wan-AI/Wan2.1-VACE-1.3B \
  --revision 574e6a744642ce3bee319afc31496b88bde8aac4 \
  --local-dir "$task_video_root/native-models/vace-1.3b"
```

以下在已准备的 Wan2.1 源码目录、已激活的独立环境运行，并必须持有 GPU 租约。输出目录需预先建立。

```bash
python generate.py --task vace-1.3B --size '832*480' \
  --frame_num 49 --ckpt_dir "$task_video_root/native-models/vace-1.3b" \
  --offload_model True --t5_cpu --sample_steps 50 \
  --sample_shift 16 --sample_guide_scale 5 --base_seed 42 \
  --prompt 'A red teapot on a wooden table gently releases steam. The camera slowly moves closer.' \
  --save_file "$task_video_root/output/native-t2v.mp4"
```

R2V 对照使用同样参数，追加以下输入并将 prompt 改为准确描述两个主体及其关系：

```text
--src_ref_images /absolute/path/teapot.png,/absolute/path/cup.png
```

V2V 对照使用同样参数，追加预处理输出（不是原视频随意改名）：

```text
--src_video /absolute/path/control.mp4
--src_mask /absolute/path/mask.mp4
```

预处理参考官方 `vace/vace_preproccess.py`，源码固定 `48eb44f1c4be87cc65a98bff985a26976841e9f3`。首帧可用 `frameref --mode firstframe`，首尾帧用 `frameref --mode firstlastframe`；灰度控制先做轻量实验，深度/人体姿态模型另列权重/许可与内存预算。`--pre_save_dir` 指定输出目录，使用返回的 src_video/src_mask 文件，不猜输出文件名。加载预处理模型也必须持有统一 GPU 租约。

## E. 实际采集与退出规则

对每个任务记录：版本、输入/输出 SHA、完整参数、冷/热启动、排队/加载/编码/推理时间、峰值 VRAM、cgroup RAM、PSI、失败码。按固定间隔采样 GPU，用 cgroup memory.peak 获取峰值，不能仅用任务结束时的 nvidia-smi。

可用的 GPU 采样命令（独立终端，任务结束停止）：

```bash
nvidia-smi --query-gpu=timestamp,memory.used,utilization.gpu \
  --format=csv --loop=1
```

文件检查：

```bash
ffprobe -v error -show_entries stream=codec_name,width,height,avg_frame_rate,nb_frames \
  -show_entries format=duration,size -of json "$task_video_root/output/native-t2v.mp4"
```

任何 OOM、服务重启、持续换页压力或原业务回归：停止后续组，不自动降低参数覆盖原记录。取消后确认 worker/Comfy 队列为空、显存回到基线，再恢复图片/标签调度。保留失败视频和脱敏日志，另建新配置继续实验。

最终按 acceptance.md 生成 mode-level 结果，发布 capability 开关。只有某个 mode/model/workflow 组合通过，才能显示“已验证”，不能按整个模型统一盖章。

## F. P1 生产接入（服务端 + App）

本节是 P0 实验手册之后的上线路径。实现与验证状态见
[P1 文档](../../verification/2026-09-14-video-generation/p1-server-app.md)。

**发布顺序**（`service/deploy/deploy_video_generation.py`，在 Ubuntu 上以 root 执行）：

```bash
# 1. 只写文件、建库与账号；不改动线上服务
python3 deploy_video_generation.py stage

# 2. 切换调度器/执行器/网关到新版本，并启动视频 Comfy（8190）与业务服务
#    此刻路由关闭、所有模式开关为 false，App 看不到可用模式
python3 deploy_video_generation.py activate

# 3. 维护窗口：暂停含 GPU 步骤的任务定义、排空在途任务，经新链路跑一次真实单图首帧任务，
#    量测成片左右边缘亮度（白边回归会 ≥0.9）；通过后才打开 FIRST_FRAME，随后恢复生产调度
python3 deploy_video_generation.py verify

# 4. 打开网关路由并设置开机自启
python3 deploy_video_generation.py enable

# 回退：停路由、关所有模式开关、还原 drop-in、停两个新单元（保留数据库与已产出视频）
python3 deploy_video_generation.py rollback
```

**窗口期不变量**：同一时刻只允许一个视频任务占用 GPU；执行器持有
`/opt/yuyutian/mytools/runtime/image-generation/gpu.lock`（与图片、标签包同一把锁），
进入前会排空两个 Comfy 队列、卸载标签模型，并要求可用显存 ≥ `VIDEO_MIN_FREE_MIB`（14848 MiB）；
退出前必须清空视频权重再放锁。

**手动回退单个模式**：把 `config/video-generation.env` 里对应的 `VIDEO_*_VALIDATED` 改成 `false`
并重启 `mytools-video-generation-service`；App 会立刻显示为"未验收"。

**播放与 Range**：成片读取支持 `Range`（206/416），网关按流式转发并保留 `Content-Range`；
原生播放器无法带登录头，因此用 `POST /api/app/v1/video-generation/jobs/{id}/ticket`
（原片用 `uploads/{id}/ticket`）换两小时票据，再交给播放器取
`/api/app/v1/video-generation/tickets/<32位十六进制>`。方案文档里的 `/api/video-generation/**`
是同一批处理器的别名。

**任务包运行时依赖**：`video_generate` 需要执行器 venv 里有 numpy（当前为 3.14 + numpy 2.5.3）；
`deploy_video_generation.py activate` 会自动检测并安装。

**排查入口**：`/opt/yuyutian/logs/mytools/video-generation-service/service.log`、
`systemctl status mytools-video-comfy mytools-video-generation-service`、
`journalctl -u mytools-task-executor-service`（执行器里的 `VIDEO_*` 错误码即任务包返回的稳定码）。
