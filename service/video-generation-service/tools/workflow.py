"""固定 VACE 1.3B 冒烟工作流；未验证模式拒绝生成。"""
import json
from pathlib import Path
import sys


def build(prompt, *, seed=42, frames=49, mode='TEXT_TO_VIDEO', reference=None,
          control=None, mask=None, prefix='p0/t2v'):
    """将受限参数映射到固定节点，禁止多图静默截断。"""
    if not isinstance(prompt, str) or not 1 <= len(prompt) <= 4000:
        raise ValueError('VIDEO_PROMPT_INVALID')
    if frames not in (49, 81) or not isinstance(seed, int) or not 0 <= seed <= 2147483647:
        raise ValueError('VIDEO_PARAMETERS_INVALID')
    references = list(reference) if isinstance(reference, (list, tuple)) else ([reference] if reference else [])
    if mode not in ('TEXT_TO_VIDEO', 'SINGLE_REFERENCE', 'SUBJECT_REFERENCES', 'FIRST_FRAME', 'FIRST_LAST_FRAMES', 'STRUCTURE_RESTYLE', 'MASKED_EDIT'):
        raise ValueError('VIDEO_MODE_NOT_VALIDATED')
    if mode == 'SUBJECT_REFERENCES':
        # 多图必须走独立适配节点；原生节点只取第一张，不能把"收下多张"当成支持多图。
        if not 2 <= len(references) <= 3 or control or mask:
            raise ValueError('VIDEO_REFERENCE_COUNT_INVALID')
    elif len(references) > 1:
        raise ValueError('VIDEO_MULTI_REFERENCE_UNSUPPORTED_BY_NATIVE_NODE')
    if mode == 'TEXT_TO_VIDEO' and references:
        raise ValueError('VIDEO_UNEXPECTED_INPUT')
    if mode == 'SINGLE_REFERENCE' and (len(references) != 1 or control or mask):
        raise ValueError('VIDEO_REFERENCE_REQUIRED')
    if mode in ('FIRST_FRAME', 'FIRST_LAST_FRAMES', 'STRUCTURE_RESTYLE', 'MASKED_EDIT') and not control:
        raise ValueError('VIDEO_CONTROL_REQUIRED')
    if mode in ('FIRST_FRAME', 'FIRST_LAST_FRAMES', 'MASKED_EDIT') and not mask:
        raise ValueError('VIDEO_MASK_REQUIRED')
    for filename in (*references, control, mask, prefix):
        if filename and (not isinstance(filename, str) or Path(filename).is_absolute() or '..' in Path(filename).parts):
            raise ValueError('VIDEO_PATH_INVALID')
    def node(kind, **inputs):
        return {'class_type': kind, 'inputs': inputs}
    graph = {
        '1': node('UNETLoader', unet_name='wan2.1_vace_1.3B_fp16.safetensors', weight_dtype='default'),
        '2': node('CLIPLoader', clip_name='umt5_xxl_fp8_e4m3fn_scaled.safetensors', type='wan', device='default'),
        '3': node('VAELoader', vae_name='wan_2.1_vae.safetensors'),
        '4': node('CLIPTextEncode', clip=['2', 0], text=prompt),
        '5': node('CLIPTextEncode', clip=['2', 0], text='blurry, static, distorted, deformed, text, watermark, flickering'),
        '6': node('WanVaceToVideo', positive=['4', 0], negative=['5', 0], vae=['3', 0],
                  width=832, height=480, length=frames, batch_size=1, strength=1.0),
        '7': node('ModelSamplingSD3', model=['1', 0], shift=16.0),
        '8': node('KSampler', model=['7', 0], seed=seed, steps=50, cfg=5.0,
                  sampler_name='uni_pc', scheduler='simple', positive=['6', 0], negative=['6', 1],
                  latent_image=['6', 2], denoise=1.0),
        '9': node('TrimVideoLatent', samples=['8', 0], trim_amount=['6', 3]),
        '10': node('VAEDecodeTiled', samples=['9', 0], vae=['3', 0], tile_size=256,
                   overlap=64, temporal_size=16, temporal_overlap=4),
        '11': node('SaveImage', images=['10', 0], filename_prefix=prefix),
    }
    if mode == 'SUBJECT_REFERENCES':
        # 独立工作流版本：多张参考图合并成一个批次交给派生节点，逐图保留身份。
        graph['6']['class_type'] = 'WanVaceMultiReferenceToVideo'
        merged = None
        for offset, filename in enumerate(references):
            graph[str(20 + offset)] = node('LoadImage', image=filename)
            if merged is None:
                merged = [str(20 + offset), 0]
            else:
                graph[str(30 + offset)] = node('ImageBatch', image1=merged, image2=[str(20 + offset), 0])
                merged = [str(30 + offset), 0]
        graph['6']['inputs']['reference_image'] = merged
    elif references:
        graph['12'] = node('LoadImage', image=references[0])
        graph['6']['inputs']['reference_image'] = ['12', 0]
    if control:
        graph['13'] = node('LoadVideo', file=control)
        graph['14'] = node('GetVideoComponents', video=['13', 0])
        graph['6']['inputs']['control_video'] = ['14', 0]
    if mask:
        graph['15'] = node('LoadVideo', file=mask)
        graph['16'] = node('GetVideoComponents', video=['15', 0])
        graph['17'] = node('ImageToMask', image=['16', 0], channel='red')
        graph['6']['inputs']['control_masks'] = ['17', 0]
    return graph


if __name__ == '__main__':
    Path(sys.argv[1]).write_text(json.dumps(build(
        'A red ceramic teapot on a wooden table gently releases steam. The camera slowly moves closer. Warm soft daylight.'), indent=2) + '\n')
