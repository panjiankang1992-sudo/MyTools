"""多图主体参考的参考帧编码。

原生 `WanVaceToVideo` 使用 `reference_image[:1]`，第二张及之后的参考图会被静默丢弃，
因此"接口收下多张图"不等于支持多图。本模块按 VACE 官方语义把每张参考图编码成一个
独立的参考潜帧，再沿时间维拼接，保持各图身份不融合、不截断。

参考帧在原生节点中的语义是掩码 0（完全保留、不参与去噪），所以参考帧数量同时决定
latent 长度增量与 `trim_latent` 裁剪量，两者必须由同一数值推导，不能分别硬编码。
"""

import torch


def encode_reference_frames(reference_image, vae, width, height, upscale, latent_format):
    """把一批参考图逐张编码为参考潜帧，返回形状 (1, 32, 参考帧数, h, w) 的潜张量。

    :param reference_image: (N, H, W, C) 的参考图批次，N 为参考图数量
    :param vae: 视频 VAE，逐张编码以避免把多图当成一个视频批次
    :param width: 目标宽度，与采样尺寸一致
    :param height: 目标高度，与采样尺寸一致
    :param upscale: 注入 `comfy.utils.common_upscale`，便于脱离 ComfyUI 测试
    :param latent_format: 注入 `comfy.latent_formats.Wan21` 实例，与原生节点一致
    :return: 每个参考图各占一个潜帧的潜张量
    """
    frame_count = int(reference_image.shape[0])
    if frame_count < 1:
        raise ValueError('VIDEO_REFERENCE_EMPTY')
    frames = []
    for index in range(frame_count):
        # 逐张缩放与编码，与原生单图路径完全一致，避免批次语义改变参考含义。
        image = reference_image[index:index + 1]
        image = upscale(image.movedim(-1, 1), width, height, "bilinear", "center").movedim(1, -1)
        latent = vae.encode(image[:, :, :, :3])
        frames.append(torch.cat([latent, latent_format.process_out(torch.zeros_like(latent))], dim=1))
    return torch.cat(frames, dim=2)
