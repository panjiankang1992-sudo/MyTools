# Wan 2.2 5B 候选验收（2026-09-28）

已完成 6 条图生、3 条纯文生，9/9 生成成功；873 帧联系图经 AI 审查，并复核了部分原尺寸关键帧。全部成片为 97 帧、24 fps，ffmpeg 整段解码通过。**候选部分改善，尚未开放正式模式，也不是 App 全链路验收通过。**

## 图生结果

| 素材 | 种子 | 耗时 | 当前质量结论 |
|---|---:|---:|---|
| 人像 | 42 | 195.02 秒 | 没有 LTX 基线的持续闭眼低头；微笑连贯，但有额外眨眼和轻微头部运动 |
| 人像 | 932 | 176.89 秒 | 主体和背景稳定，微笑清晰；眨眼次数、头部固定仍不完全符合 |
| 商品 | 42 | 195.01 秒 | 有明显推进放大，主体颜色保持；构图和细节仍需播放复核 |
| 商品 | 932 | 194.99 秒 | 有放大，不能只凭横向位移断定推进失败；构图和细节需复核 |
| 风景 | 42 | 138.82 秒 | 山体轮廓和光照改变，未满足仅水面运动、场景保持要求 |
| 风景 | 932 | 120.71 秒 | 原尺寸可见局部山体形变及高光变化，不能全部归因于整体镜头横移 |

已补齐三类主体 × 两个种子，但联系图不替代完整播放两遍，也不替代正式评分、连续任务稳定性和 App 验收。

## 文生结果

| 样例 | 种子 | 耗时 | 当前质量结论 |
|---|---:|---:|---|
| 茶壶冒蒸汽 | 42 | 191.04 秒 | 有持续蒸汽，但容器更像敞口壶/罐，蒸汽不从明确壶嘴冒出，纯色背景出现额外亮块 |
| 成年人挥手 | 42 | 172.93 秒 | 右手挥动、放下及微笑可辨，主体和背景较稳；准确次数仍需播放核对 |
| 树叶随风摆动 | 42 | 118.67 秒 | 树枝和叶片连续运动，主体可辨；细致机位与运动质量需播放复核 |

文生使用独立提示词，不读取图片夹具。已逐一核查实际提交图没有 LoadImage 节点和 start_image 连接。第二种子尚未执行，不能记为完整模式验收通过。

## 判读更正

上一轮基于联系图，把商品画面位移直接判为横移，结论过强。原尺寸首/中/末帧显示整体放大；辅助二维拟合的缩放量约为 1.225（42）和 1.075（932）。推进本身可产生屏幕横向位移，此度量也不能证明三维相机轨迹。

风景原尺寸复核显示局部山体与背景变化，不能简单统称镜头横移。原视频未改动，旧判断的更正记录在 `review.json/corrections`；`first-middle-last.png` 和 `*-keyframes.png` 保留复核图。度量仅辅助判断，不自动授予质量通过。

## 资源与恢复

RTX 5060 Ti，显存 16311 MiB，内存 31364 MiB。下载官方 Wan 2.2 TI2V 5B 和专用 VAE 后，完整长度及 SHA-256 均匹配 `model-lock.json`；复用现有 UMT5 FP8 和原生节点，没有升级 ComfyUI。

全部五个候选窗口 restored=true。当前记录的显存采样峰值最高 13755 MiB、可用内存采样最低 11257 MiB。采样间隔约 5 秒，不是瞬时绝对峰值。最后检查图片、视频服务均 active，ComfyUI 运行及等待队列为空。

主模型与 VAE 经官方文档列出的 ModelScope 镜像取得。Hugging Face 直连及既有代理本轮遇到 TLS 失败；镜像文件使用官方摘要校验，不依赖可变镜像标签确定文件身份。

## 实现与验证

- `service/deploy/wan22_candidate_graph.py`：文生和首帧候选图，20 步、CFG 5、UniPC、simple、shift 8；文本编码在 CPU，VAE 分块解码。
- `service/deploy/evaluate_ltx_duration.py`：沿用独占 GPU 窗口、竞争检查、异常恢复，新增模型/输入模式/单样例参数和内存低于 2 GB 时的中止保护。
- `service/deploy/video_candidate_cases.py`：独立文生样例，不隐式读取图片。
- `service/deploy/measure_video_framing.py`：二维缩放/平移辅助分析，不作质量评分。
- 13 项候选/用例测试、2 项已知几何变换测试通过；`git diff --check` 通过。
- `media-validation.json`：9 条成片完整解码及帧数、帧率验证；`review.json`：AI 来源、缺陷和资源；`sha256.json`：图文件及视频摘要。

复现时使用新输出目录：

```bash
python evaluate_ltx_duration.py --model wan22 --input-mode first-frame --case product --seed 42 --timeout 1800 --output <new-directory>
python evaluate_ltx_duration.py --model wan22 --input-mode text --seed 42 --timeout 1800 --output <new-directory>
```

需在既有 Ubuntu 视频虚拟环境中运行，助手脚本放在同目录。输出采用 640 长边、97 帧以对照 LTX，不是官方默认高分辨率效果复现；负面提示词也采用英文简化版本。

## 尚未完成

正式质量评分及完整播放、文生第二种子、独立素材与连续稳定性、App 真实提交/播放/保存链路仍需完成。当前候选图没有实现多参考图或视频改编。本轮未更改正式任务包、未重装 App、未放开生产模式，没有遗留后台下载或推理进程。

## 官方依据

- [Comfy 官方教程](https://docs.comfy.org/tutorials/video/wan/wan2_2)
- [官方工作流](https://github.com/Comfy-Org/workflow_templates/blob/main/templates/video_wan2_2_5B_ti2v.json)
- [扩散权重固定版本](https://huggingface.co/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/blob/5ca2dfecf59320b1d4605b5802e64f77a8676afe/split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors)
- [VAE 固定版本](https://huggingface.co/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/blob/5ca2dfecf59320b1d4605b5802e64f77a8676afe/split_files/vae/wan2.2_vae.safetensors)
