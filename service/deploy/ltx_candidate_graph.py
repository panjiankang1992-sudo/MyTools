"""实验工作流参数构建，不修改正式不可变任务包。"""
from copy import deepcopy


def configure_graph(base, width, height, variant, seed):
    """显式分离对照变量并保持固定输出尺寸和帧数。"""
    if variant not in ('baseline', 'cfg2', 'end-guide', 'guided-cfg2', 'two-stage', 'two-stage-complete'):
        raise ValueError('unknown_variant')
    if width % 32 or height % 32 or min(width, height) < 256:
        raise ValueError('unsupported_dimensions')
    if not isinstance(seed, int) or not 0 <= seed <= 2147483647:
        raise ValueError('invalid_seed')
    graph = deepcopy(base)
    graph['6']['inputs']['length'] = 97
    graph['7']['inputs']['frame_rate'] = 24.0
    if variant in ('cfg2', 'guided-cfg2'):
        graph['10']['inputs']['cfg'] = 2.0
        graph['4']['inputs']['text'] = ('camera pan, camera rotation, moving background, '
            'deformed objects, identity change, prolonged closed eyes, scene cuts')
    if variant in ('end-guide', 'guided-cfg2'):
        # 引导追加的潜帧必须在解码前移除，不能混入成片帧数。
        graph['13'] = {'class_type': 'LTXVAddGuide', 'inputs': {
            'positive': ['6', 0], 'negative': ['6', 1], 'latent': ['6', 2],
            'vae': ['1', 2], 'image': ['5', 0], 'frame_idx': 96, 'strength': 0.5 if variant == 'guided-cfg2' else 1.0}}
        graph['7']['inputs'].update(positive=['13', 0], negative=['13', 1])
        graph['10']['inputs']['latent_image'] = ['13', 2]
        graph['14'] = {'class_type': 'LTXVCropGuides', 'inputs': {
            'positive': ['7', 0], 'negative': ['7', 1], 'latent': ['10', 0]}}
        graph['11']['inputs']['samples'] = ['14', 2]
    if variant in ('two-stage', 'two-stage-complete'):
        # 官方低分辨率采样、潜空间放大、二次采样路径的原生节点适配。
        graph['6']['inputs'].update(width=max(64, round(width * 2 / 3 / 32) * 32),
                                   height=max(64, round(height * 2 / 3 / 32) * 32))
        graph['9']['inputs']['sigmas'] = '1, 0.9937, 0.9875, 0.9812, 0.975, 0.9094, 0.725'
        if variant == 'two-stage-complete':
            # 官方时间步列表隐式追加零；ComfyUI 必须显式补齐最后一次模型求值。
            graph['9']['inputs']['sigmas'] += ', 0'
        graph['15'] = {'class_type': 'LatentUpscaleModelLoader', 'inputs': {
            'model_name': 'ltxv-spatial-upscaler-0.9.8.safetensors'}}
        graph['16'] = {'class_type': 'LTXVLatentUpsampler', 'inputs': {
            'samples': ['10', 1], 'upscale_model': ['15', 0], 'vae': ['1', 2]}}
        # 通用节点内部除以八；LTX 潜空间除以三十二，因此传入四分之一像素尺寸。
        graph['17'] = {'class_type': 'LatentUpscale', 'inputs': {
            'samples': ['16', 0], 'upscale_method': 'bilinear',
            'width': width // 4, 'height': height // 4, 'crop': 'disabled'}}
        graph['18'] = {'class_type': 'LTXVImgToVideoInplace', 'inputs': {
            'latent': ['17', 0], 'image': ['5', 0], 'vae': ['1', 2], 'strength': 1.0, 'bypass': False}}
        graph['19'] = {'class_type': 'ManualSigmas', 'inputs': {'sigmas': '0.9094, 0.725, 0.4219, 0'}}
        graph['20'] = {'class_type': 'SamplerCustom', 'inputs': {
            'model': ['1', 0], 'add_noise': True, 'noise_seed': seed, 'cfg': 1.0,
            'positive': ['7', 0], 'negative': ['7', 1], 'sampler': ['8', 0],
            'sigmas': ['19', 0], 'latent_image': ['18', 0]}}
        graph['11']['inputs']['samples'] = ['20', 0]
    return graph
