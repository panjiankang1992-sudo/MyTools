# LTX 参数与条件对照（2026-09-27）

本轮在共享 GPU 的可恢复窗口内完成 18 条完整对照真实推理，全部成功；AI 查看全部 1746 帧联系图。仅部分指标改善，不能记为质量验收通过，生产门禁未变。

| 方案 | 样例数 | 结论 |
|---|---:|---|
| CFG=2 + 负面提示词 | 3 | 人像闭眼时间缩短，但仍有头部/镜头偏移；产品前推进变为横移；风景水波改善但机位仍偏移 |
| 尾帧原图强约束 | 2 | 构图稳定性改善，但人像表情回退、重复闭眼，水面运动被压制 |
| CFG=2 + 0.5 尾帧约束 | 4 | 两种子复验，效果不一致；稳定构图与期望运动未同时达标 |
| 两阶段空间放大与重采样 | 3 | 输出规格正确，但产品横移、人像持续闭眼低头、风景上移，未通过 |

均为 97 帧 / 24 fps、现有 LTX 2B distilled 与同一组已用独立素材。CFG、负面提示词与关键帧改变了多个因素，因此结果只用于筛选方案，不能把改善归因于单一参数。尾帧原图仅用于固定机位场景，未套用到要求镜头推进的产品场景。

脚本没有改动生产不可变包或开关。七个完整对照窗口 restored=true，详见各目录 window.json、runs.json。质量记录见 review.json，按 AI 来源记录，未请求用户评分。

## 两阶段核对

官方配置使用低分辨率采样、空间放大、第二阶段采样。已完成对应的原生 ComfyUI 节点适配与三类素材对照；这不是完整复制官方 Python pipeline（潜空间尺寸适配等仍需分别核对），不能提前声称等价。

- [LTX 官方 2B 配置](https://github.com/Lightricks/LTX-Video/blob/main/configs/ltxv-2b-0.9.8-distilled.yaml)
- [ComfyUI 原生 LTX 节点](https://github.com/Comfy-Org/ComfyUI/blob/master/comfy_extras/nodes_lt.py)
- 新增图构建测试 4 项通过：节点引用、97 帧约束、引导帧解码前移除、两阶段潜空间倍率及无效参数。

是否开放明确标注限制的试用功能已向用户询问；未得到答复前保持当前生产开关，不把沉默视为同意。

## 本轮结论与复现

两阶段三条耗时分别 14.43、10.33、8.30 秒，均输出 97 帧；产品、人像为 640×640，风景为 640×448。所有帧尺寸验证通过。空间放大模型使用锁定的官方版本并校验完整 SHA-256，见 upscaler-lock.json。新增图构建测试与现有 LTX 测试合计 7 项通过。

可使用 `service/deploy/evaluate_ltx_duration.py --variant two-stage --output <experiment-directory>` 在既有 Ubuntu runtime 下复现；依赖同目录的图构建、候选评估及 GPU 窗口助手。生成与质量结果分别记录；原始 summary.json 中 quality=pending 是生成阶段输出，最终 AI 结论以本目录 review.json 为准。

本轮属于直接 ComfyUI 候选实验，不是 App 到调度器的端到端验收。18/18 完整对照生成成功不能替代动作、镜头和主体质量通过。保持生产门禁关闭；后续应优先验证其他模型能力，避免继续把构图稳定但近乎静止的视频判为合格。结束后检查图片与视频服务均 active，ComfyUI 运行及等待队列均为空。

## 后续修正：第一阶段末次去噪

核查 Ubuntu 当前安装源码确认，VAE 已默认使用 decode_timestep=0.05、decode_noise_scale=0.025，排除此前对解码默认值不同的猜测。

发现旧 two-stage 图把官方的模型求值时间步直接当作 ComfyUI 的区间端点，未显式追加零，因此漏掉 sigma=0.725 的最后一次求值。新增 two-stage-complete 对照，在第一阶段末尾追加零；保留旧变体和原始输出以便复现。新增测试检查七次求值和严格递减序列，LTX 测试合计 8 项通过。

依据：[官方 RectifiedFlowScheduler.step](https://github.com/Lightricks/LTX-Video/blob/main/ltx_video/schedulers/rf.py) 会向模型时间步列表追加零，计算最后的去噪区间。

补齐末步后的同素材对照为 3/3 成功，耗时 14.34、10.34、8.29 秒；逐帧检查仍出现产品横移、人像持续闭眼低头、风景镜头上移。另一次 SSH 断开仅完成产品样例，窗口 restored=true，未计入完整对照数。

在修正后的同一图、种子和素材上，新增 `--prompt-style action-first` 按动作优先及时间顺序改写提示词；依据 [官方提示词建议](https://github.com/Lightricks/LTX-Video#-prompt-engineering)。3/3 成功，耗时 14.34、10.32、8.32 秒；末尾微笑更明显，但持续闭眼与镜头漂移未解决。提示词对照未通过。两组共 582 帧联系图经 AI 检查；视频与图的 SHA-256 见 followup-sha256.json。

修正后的可复现命令为 `evaluate_ltx_duration.py --variant two-stage-complete --prompt-style action-first --output <new-directory>`。旧 two-stage 仅用于历史对照，不能作为官方完整七步第一阶段的实现使用。该修正属于候选评估脚本，不是正式 App 更新，也未重新安装 App。
