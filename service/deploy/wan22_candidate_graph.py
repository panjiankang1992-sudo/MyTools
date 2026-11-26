"""Wan 2.2 5B 原生候选图；不更改正式任务包或开放开关。"""


def graph(image, prefix, seed, width=640, height=640, prompt='', frames=97):
    """使用官方采样参数，支持首帧和纯文本，固定可复核的输出节点。"""
    if min(width, height) < 256 or width % 32 or height % 32:
        raise ValueError('unsupported_dimensions')
    if frames < 5 or frames > 121 or (frames - 1) % 4:
        raise ValueError('unsupported_frames')
    if not isinstance(seed, int) or not 0 <= seed <= 2147483647:
        raise ValueError('invalid_seed')
    def node(kind, **inputs):
        return {'class_type': kind, 'inputs': inputs}
    result = {
        '1': node('UNETLoader', unet_name='wan2.2_ti2v_5B_fp16.safetensors', weight_dtype='default'),
        '2': node('CLIPLoader', clip_name='umt5_xxl_fp8_e4m3fn_scaled.safetensors', type='wan', device='cpu'),
        '3': node('CLIPTextEncode', clip=['2', 0], text=prompt),
        '4': node('CLIPTextEncode', clip=['2', 0], text='oversaturated, overexposed, blurry, subtitles, low quality, deformed face, deformed limbs, scene cuts'),
        '6': node('Wan22ImageToVideoLatent', vae=['7', 0], width=width, height=height, length=frames, batch_size=1),
        '7': node('VAELoader', vae_name='wan2.2_vae.safetensors'),
        '8': node('ModelSamplingSD3', model=['1', 0], shift=8.0),
        '10': node('KSampler', model=['8', 0], seed=seed, steps=20, cfg=5.0,
                   sampler_name='uni_pc', scheduler='simple', denoise=1.0,
                   positive=['3', 0], negative=['4', 0], latent_image=['6', 0]),
        '11': node('VAEDecodeTiled', samples=['10', 0], vae=['7', 0], tile_size=256,
                   overlap=64, temporal_size=32, temporal_overlap=8),
        '12': node('SaveImage', images=['11', 0], filename_prefix=prefix),
    }
    if image is not None:
        # 首帧可选；纯文本路径不留下无效图像依赖。
        result['5'] = node('LoadImage', image=image)
        result['6']['inputs']['start_image'] = ['5', 0]
    return result
