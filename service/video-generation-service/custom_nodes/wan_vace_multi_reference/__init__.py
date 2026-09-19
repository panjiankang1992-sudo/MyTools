"""固定版本的多图主体参考 VACE 节点。

本节点是对 ComfyUI `comfy_extras/nodes_wan.py` 中 `WanVaceToVideo` 的最小派生：除参考图
编码外，控制视频、控制掩码、掩码重采样、条件注入与 `trim_latent` 推导都保持逐行一致，
以便用相同的 seed 与原生节点做等价性对照。

只通过 `--whitelist-custom-nodes wan_vace_multi_reference` 显式加载，不使用 Manager
自动安装任意插件；加载目录、文件与提交版本都必须记录 SHA-256。
"""

import torch
import nodes
import node_helpers
import comfy.model_management
import comfy.utils
import comfy.latent_formats
from typing_extensions import override
from comfy_api.latest import ComfyExtension, io

from .reference_frames import encode_reference_frames


class WanVaceMultiReferenceToVideo(io.ComfyNode):
    """与原生 `WanVaceToVideo` 输入输出相同，但保留全部参考图而不是只用第一张。"""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="WanVaceMultiReferenceToVideo",
            search_aliases=["video conditioning", "video control", "multi reference"],
            category="model/conditioning/wan/vace",
            inputs=[
                io.Conditioning.Input("positive"),
                io.Conditioning.Input("negative"),
                io.Vae.Input("vae"),
                io.Int.Input("width", default=832, min=16, max=nodes.MAX_RESOLUTION, step=16),
                io.Int.Input("height", default=480, min=16, max=nodes.MAX_RESOLUTION, step=16),
                io.Int.Input("length", default=81, min=1, max=nodes.MAX_RESOLUTION, step=4),
                io.Int.Input("batch_size", default=1, min=1, max=4096),
                io.Float.Input("strength", default=1.0, min=0.0, max=1000.0, step=0.01),
                io.Image.Input("control_video", optional=True),
                io.Mask.Input("control_masks", optional=True),
                io.Image.Input("reference_image", optional=True),
            ],
            outputs=[
                io.Conditioning.Output(display_name="positive"),
                io.Conditioning.Output(display_name="negative"),
                io.Latent.Output(display_name="latent"),
                io.Int.Output(display_name="trim_latent"),
            ],
        )

    @classmethod
    def execute(cls, positive, negative, vae, width, height, length, batch_size, strength, control_video=None, control_masks=None, reference_image=None) -> io.NodeOutput:
        """构建 VACE 条件与初始潜张量，参考图数量决定 latent 增量与裁剪量。"""
        latent_length = ((length - 1) // 4) + 1
        if control_video is not None:
            control_video = comfy.utils.common_upscale(control_video[:length].movedim(-1, 1), width, height, "bilinear", "center").movedim(1, -1)
            if control_video.shape[0] < length:
                control_video = torch.nn.functional.pad(control_video, (0, 0, 0, 0, 0, 0, 0, length - control_video.shape[0]), value=0.5)
        else:
            control_video = torch.ones((length, height, width, 3)) * 0.5

        reference_latent = None
        if reference_image is not None:
            reference_latent = encode_reference_frames(reference_image, vae, width, height,
                                                       comfy.utils.common_upscale, comfy.latent_formats.Wan21())

        if control_masks is None:
            mask = torch.ones((length, height, width, 1))
        else:
            mask = control_masks
            if mask.ndim == 3:
                mask = mask.unsqueeze(1)
            mask = comfy.utils.common_upscale(mask[:length], width, height, "bilinear", "center").movedim(1, -1)
            if mask.shape[0] < length:
                mask = torch.nn.functional.pad(mask, (0, 0, 0, 0, 0, 0, 0, length - mask.shape[0]), value=1.0)

        control_video = control_video - 0.5
        inactive = (control_video * (1 - mask)) + 0.5
        reactive = (control_video * mask) + 0.5

        inactive = vae.encode(inactive[:, :, :, :3])
        reactive = vae.encode(reactive[:, :, :, :3])
        control_video_latent = torch.cat((inactive, reactive), dim=1)
        if reference_latent is not None:
            control_video_latent = torch.cat((reference_latent, control_video_latent), dim=2)

        vae_stride = 8
        height_mask = height // vae_stride
        width_mask = width // vae_stride
        mask = mask.view(length, height_mask, vae_stride, width_mask, vae_stride)
        mask = mask.permute(2, 4, 0, 1, 3)
        mask = mask.reshape(vae_stride * vae_stride, length, height_mask, width_mask)
        mask = torch.nn.functional.interpolate(mask.unsqueeze(0), size=(latent_length, height_mask, width_mask), mode='nearest-exact').squeeze(0)

        trim_latent = 0
        if reference_latent is not None:
            # 参考帧掩码为 0，长度增量与裁剪量都必须取自实际参考帧数。
            mask_pad = torch.zeros_like(mask[:, :reference_latent.shape[2], :, :])
            mask = torch.cat((mask_pad, mask), dim=1)
            latent_length += reference_latent.shape[2]
            trim_latent = reference_latent.shape[2]

        mask = mask.unsqueeze(0)

        positive = node_helpers.conditioning_set_values(positive, {"vace_frames": [control_video_latent], "vace_mask": [mask], "vace_strength": [strength]}, append=True)
        negative = node_helpers.conditioning_set_values(negative, {"vace_frames": [control_video_latent], "vace_mask": [mask], "vace_strength": [strength]}, append=True)

        latent = torch.zeros([batch_size, 16, latent_length, height // 8, width // 8], device=comfy.model_management.intermediate_device())
        out_latent = {}
        out_latent["samples"] = latent
        return io.NodeOutput(positive, negative, out_latent, trim_latent)


class WanVaceMultiReferenceExtension(ComfyExtension):
    """向固定版本 ComfyUI 注册多图参考节点。"""

    @override
    async def get_node_list(self) -> list[type[io.ComfyNode]]:
        return [WanVaceMultiReferenceToVideo]


async def comfy_entrypoint() -> WanVaceMultiReferenceExtension:
    """ComfyUI 自定义节点入口。"""
    return WanVaceMultiReferenceExtension()
