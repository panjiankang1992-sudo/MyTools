#!/usr/bin/env python3
"""受管视频执行器：只运行通过验收的模式，任何失败都不切换供应方。

执行链固定为：验收开关 → 参数形状 → 素材逐帧解码预检 → 控制信号预处理 → 共享显存租约
→ 提交固定工作流 → 轮询到结束 → 取回帧 → 编码 MP4 与封面 → 原子写 result.json。
客户端不能提交节点图、不能指定采样参数，也不能指定上游地址。
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import traceback
import uuid

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import comfy
import vace_control as vc
import workflow as wf
from gpu_lease import GpuLease, gpu_used_mib

# 模式到验收开关与素材角色的固定映射，与服务端 VideoCapability 一一对应。
MODES = {
    'TEXT_TO_VIDEO': {'flag': 'VIDEO_T2V_VALIDATED', 'roles': (), 'duration': 0},
    'FIRST_FRAME': {'flag': 'VIDEO_FIRST_FRAME_VALIDATED', 'roles': ('FIRST_FRAME',), 'duration': 0},
    'SUBJECT_REFERENCES': {'flag': 'VIDEO_REFERENCES_VALIDATED', 'roles': ('SUBJECT', 'SUBJECT'), 'duration': 0},
    'FIRST_LAST_FRAMES': {'flag': 'VIDEO_FIRST_LAST_VALIDATED',
                          'roles': ('FIRST_FRAME', 'LAST_FRAME'), 'duration': 0},
    'STRUCTURE_RESTYLE': {'flag': 'VIDEO_RESTYLE_VALIDATED', 'roles': ('SOURCE_VIDEO',), 'duration': 1},
    'MASKED_EDIT': {'flag': 'VIDEO_MASKED_VALIDATED', 'roles': ('SOURCE_VIDEO', 'MASK_VIDEO'), 'duration': 2},
}
SIZE = '832x480'
FRAMES = 49
FPS = 16
MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_VIDEO_BYTES = 200 * 1024 * 1024


class Rejected(Exception):
    """可预期的输入或门禁拒绝，映射为稳定错误码。"""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def safe_child(root, *parts):
    """阻止受管目录中的符号链接改变文件归属。"""
    path = root
    for part in parts:
        path = path / part
        if path.is_symlink():
            raise Rejected('VIDEO_PATH_INVALID')
    if not path.resolve().is_relative_to(root.resolve()):
        raise Rejected('VIDEO_PATH_INVALID')
    return path


def atomic_write(path, payload, binary=True):
    """先写临时文件再替换，避免服务读到半截产物。"""
    temporary = path.with_name(path.name + '.tmp')
    with temporary.open('wb' if binary else 'w') as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def root_dir():
    """受管根目录；上传素材与产物都必须在它下面。"""
    root = Path(os.environ['VIDEO_GENERATION_ROOT']).resolve(strict=True)
    return root


def require_validated(parameters):
    """先过全局门禁再过模式门禁；未验收模式一律拒绝。"""
    if os.environ.get('VIDEO_LOCAL_VALIDATED') != 'true' or os.environ.get('VIDEO_GPU_COORDINATION_VALIDATED') != 'true':
        raise Rejected('VIDEO_LOCAL_VALIDATION_REQUIRED')
    mode = parameters.get('mode')
    entry = MODES.get(mode)
    if entry is None:
        raise Rejected('VIDEO_MODE_NOT_VALIDATED')
    # 每个模式有独立的验收开关：整体验收不代表每个模式都能对外提供。
    if os.environ.get(entry['flag']) != 'true':
        raise Rejected('VIDEO_MODE_VALIDATION_REQUIRED')
    if parameters.get('resourceId') != 'wan-vace-1.3b-local':
        raise Rejected('VIDEO_PROVIDER_VALIDATION_REQUIRED')
    return mode, entry


def validate_parameters(parameters, entry):
    """校验形状与配额；尺寸、帧率、帧数都是固定常量。"""
    prompt = parameters.get('prompt')
    if not isinstance(prompt, str) or not 1 <= len(prompt.strip()) <= 4000:
        raise Rejected('VIDEO_PROMPT_INVALID')
    seed = parameters.get('seed')
    if not isinstance(seed, int) or isinstance(seed, bool) or not 0 <= seed <= 2147483647:
        raise Rejected('VIDEO_PARAMETERS_INVALID')
    output = parameters.get('output')
    if not isinstance(output, dict) or output.get('size') != SIZE or output.get('frames') != FRAMES \
            or output.get('fps') != FPS:
        raise Rejected('VIDEO_PARAMETERS_INVALID')
    if parameters.get('width') != 832 or parameters.get('height') != 480:
        raise Rejected('VIDEO_PARAMETERS_INVALID')
    # 原声属于 P0 判为不可用的能力，必须在提交前拒绝而不是产出一段静音视频。
    if parameters.get('audioPolicy') != 'SILENT':
        raise Rejected('VIDEO_AUDIO_UNSUPPORTED')
    inputs = parameters.get('resolvedInputs')
    if not isinstance(inputs, list):
        raise Rejected('VIDEO_INPUT_INVALID')
    if tuple(item.get('role') for item in inputs) != entry['roles']:
        raise Rejected('VIDEO_INPUT_INVALID')
    return prompt.strip(), seed, inputs


def input_files(root, inputs):
    """定位并复核每个素材文件；路径只能来自受管上传目录。"""
    resolved = []
    for ordinal, item in enumerate(inputs):
        upload_id = str(uuid.UUID(str(item.get('uploadId'))))
        path = safe_child(root, 'uploads', upload_id)
        if not path.is_file():
            raise Rejected('VIDEO_INPUT_MISSING')
        size = path.stat().st_size
        limit = MAX_VIDEO_BYTES if item.get('role') in ('SOURCE_VIDEO', 'MASK_VIDEO') else MAX_IMAGE_BYTES
        if not 0 < size <= limit:
            raise Rejected('VIDEO_INPUT_INVALID')
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        # 服务端登记过摘要，这里必须一致，否则说明文件在传输中被截断或替换。
        if item.get('sha256') and digest != item['sha256']:
            raise Rejected('VIDEO_INPUT_MISMATCH')
        resolved.append({'ordinal': ordinal, 'role': item.get('role'), 'path': path, 'sha256': digest,
                         'bytes': size, 'trimStartMs': item.get('trimStartMs'),
                         'trimEndMs': item.get('trimEndMs')})
    return resolved


def prepare_control(mode, files, work, width, height, frames):
    """生成控制视频；（需要时）生成掩码视频。返回 manifest 片段。"""
    manifest = {'padColor': vc.PAD_COLOR_HEX}
    if mode in ('FIRST_FRAME', 'FIRST_LAST_FRAMES'):
        images = np.concatenate([vc.decode_image(item['path'], width, height) for item in files])
        stacked = vc.fit_frames(images, frames)
        indices = [0] if mode == 'FIRST_FRAME' else [0, frames - 1]
        control, mask = vc.reference_frames(stacked, indices, 'blend', vc.DEFAULT_FILL_BLEND)
        manifest.update(referenceFill='blend', fillBlend=vc.DEFAULT_FILL_BLEND,
                        preservedFrameIndices=indices)
    elif mode == 'STRUCTURE_RESTYLE':
        source = files[0]
        decoded = vc.decode(source['path'], width, height, 'gray',
                            start_ms=source['trimStartMs'] or 0, limit=frames)
        stacked = vc.fit_frames(decoded, frames)
        # 暗且低纹理的源片会把背景压黑，直接拒绝而不是照跑。
        quality = vc.require_restyle_source(stacked)
        control, mask = vc.luma(stacked), None
        manifest.update(signal='gray', sourceQuality=quality)
    elif mode == 'MASKED_EDIT':
        source, mask_item = files[0], files[1]
        decoded = vc.decode(source['path'], width, height, 'raw',
                            start_ms=source['trimStartMs'] or 0, limit=frames)
        control = vc.fit_frames(decoded, frames).astype(np.float32)
        mask = vc.decode_mask(mask_item['path'], width, height, frames,
                              start_ms=mask_item['trimStartMs'] or 0)
        manifest.update(signal='raw', preservedFrameIndices=[])
    else:
        raise Rejected('VIDEO_PREPROCESS_MODE_UNSUPPORTED')
    # 补边几何以控制帧为准；模型会给成片的补边改色，出片后按这个几何还原。
    rects = vc.detect_pad_rects(np.rint(control[0]).clip(0, 255).astype(np.uint8))
    left, right, top, bottom = rects
    manifest['padRects'] = {'left': left, 'right': right, 'top': top, 'bottom': bottom}
    manifest['padRestoreFilter'] = vc.restore_filter(rects, width, height)
    control_path = work / 'control.mp4'
    vc.encode(control_path, control, FPS)
    manifest['controlSha256'] = vc.digest(control_path)
    mask_path = None
    if mask is not None:
        mask_path = work / 'mask.mp4'
        # 单通道掩码复制到三通道，工作流按红通道还原为掩码。
        vc.encode(mask_path, np.repeat(mask[..., None], 3, axis=-1), FPS)
        manifest['maskSha256'] = vc.digest(mask_path)
    return control_path, mask_path, manifest


class PromptCleanup:
    """取消时只中断本次租约提交的任务，不影响其他 Comfy 工作。"""

    def __init__(self, base):
        self.base = base
        self.prompt_id = None
        self.previous = None

    def __enter__(self):
        self.previous = signal.signal(signal.SIGTERM, self.terminate)
        return self

    def terminate(self, *args):
        raise SystemExit(143)

    def __exit__(self, *args):
        signal.signal(signal.SIGTERM, self.previous)
        try:
            queue = comfy.call(self.base, '/queue')
            running = [item[1] for item in queue.get('queue_running', [])]
            pending = [item[1] for item in queue.get('queue_pending', [])]
            if self.prompt_id in pending:
                comfy.call(self.base, '/queue', {'delete': [self.prompt_id]})
            if self.prompt_id is not None and running == [self.prompt_id]:
                comfy.call(self.base, '/interrupt', {})
        except Exception:
            # 取消路径的网络异常不改变任务结果；显存租约仍会阻止下一次误加载。
            pass


def await_frames(base, graph, output_node, cleanup, frames, timeout):
    """提交并轮询到结束，返回 (prompt_id, 帧信息, 采样到的显存峰值 MiB)。"""
    short = str(uuid.uuid4())
    prompt_id = comfy.submit(base, graph, short)
    cleanup.prompt_id = prompt_id
    deadline = time.monotonic() + timeout
    peak = 0
    while True:
        # 只有轮询期间能观察到推理的显存峰值；租约释放后读数会立刻掉回空闲。
        # 采样属于诊断信息：单次读取失败不能中断已经在跑的推理。
        try:
            peak = max(peak, gpu_used_mib())
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        history = comfy.call(base, '/history/' + prompt_id, timeout=60).get(prompt_id)
        if history:
            status = history.get('status', {})
            if status.get('status_str') != 'success' or not status.get('completed'):
                raise Rejected('VIDEO_INFERENCE_FAILED')
            images = history.get('outputs', {}).get(output_node, {}).get('images')
            if not isinstance(images, list) or len(images) != frames:
                raise Rejected('VIDEO_FRAME_COUNT_MISMATCH')
            return prompt_id, images, peak
        if time.monotonic() > deadline:
            raise Rejected('VIDEO_INFERENCE_TIMEOUT')
        time.sleep(5)


def collect_frames(images, output_root, frames):
    """把产物帧解析到受管输出目录之内，并逐帧校验 PNG 头。"""
    paths = []
    for image in images:
        subfolder = image.get('subfolder') or ''
        filename = image.get('filename') or ''
        if Path(filename).name != filename or '..' in subfolder.split('/') or subfolder.startswith('/'):
            raise Rejected('VIDEO_OUTPUT_PATH_INVALID')
        path = (output_root / subfolder / filename).resolve()
        if not path.is_relative_to(output_root.resolve()) or not path.is_file():
            raise Rejected('VIDEO_OUTPUT_PATH_INVALID')
        with path.open('rb') as stream:
            if stream.read(8) != b'\x89PNG\r\n\x1a\n':
                raise Rejected('VIDEO_OUTPUT_INVALID')
        paths.append(path)
    if len(paths) != frames:
        raise Rejected('VIDEO_FRAME_COUNT_MISMATCH')
    return paths


def encode_video(paths, target, work, restore=None):
    """用 concat 解复用器把帧序列编码为 H.264/yuv420p，并加上 faststart。

    `restore` 是把补边还原为中性灰的滤镜：模型会把补边区改成任意颜色，这里按控制帧量出的
    几何覆盖回去，保证成片两侧仍是约定的中性灰。
    """
    listing = work / 'frames.txt'
    listing.write_text(''.join(f"file '{path}'\nduration {1 / FPS:.6f}\n" for path in paths))
    args = ['ffmpeg', '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', str(listing)]
    if restore:
        args += ['-vf', restore]
    args += ['-r', str(FPS), '-frames:v', str(FRAMES), '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
             '-movflags', '+faststart', str(target)]
    subprocess.run(args, check=True, timeout=900)


def write_cover(source, target, restore):
    """从生成帧写出封面；与成片共用同一套补边还原滤镜，避免封面留着被模型改色的边。"""
    args = ['ffmpeg', '-v', 'error', '-y', '-i', str(source), '-frames:v', '1']
    if restore:
        args += ['-vf', restore]
    args += [str(target)]
    subprocess.run(args, check=True, timeout=120)


def memory_available_mib():
    """读取主机可用内存；资源证据属于诊断信息，读不到时返回 0 而不是让已完成的生成失败。"""
    try:
        for line in Path('/proc/meminfo').read_text().splitlines():
            if line.startswith('MemAvailable:'):
                return int(line.split()[1]) // 1024
    except OSError:
        return 0
    return 0


def generate(parameters):
    """执行一次视频任务，返回符合 result.schema.json 的结果对象。"""
    mode, entry = require_validated(parameters)
    prompt, seed, inputs = validate_parameters(parameters, entry)
    root = root_dir()
    job = str(uuid.UUID(str(parameters['jobId'])))
    output = safe_child(root, 'outputs', job)
    output.mkdir(parents=True, exist_ok=True)
    work = safe_child(root, 'work', job)
    work.mkdir(parents=True, exist_ok=True)
    width, height = (int(part) for part in SIZE.split('x'))
    files = input_files(root, inputs)
    control_path, mask_path, manifest = prepare_control(mode, files, work, width, height, FRAMES)
    # 规格版本由部署固定，客户端不能要求换一套工作流。
    entry_index = wf.index()
    if entry_index.get('revision') != parameters.get('workflowRevision'):
        raise Rejected('VIDEO_WORKFLOW_REVISION_MISMATCH')
    value = wf.spec(mode, FRAMES)
    base = comfy.base_url()
    with GpuLease(), PromptCleanup(base) as cleanup:
        # 从拿到租约开始计时：这段区间就是 GPU 被本次任务占住的时间（不含 MP4 编码）。
        started = time.monotonic()
        uploaded = []
        for item in files:
            # 首帧/尾帧/源片与掩码都通过预处理产物进入工作流，不直接上传原文件。
            if item['role'] != 'SUBJECT':
                continue
            # 扁平且唯一的文件名既是校验通过的前提，也避免不同任务互相覆盖输入。
            name = f'mytools-{job}-{item["ordinal"]}{item["path"].suffix.lower()}'
            uploaded.append(comfy.upload_file(base, item['path'], name))
        bindings = {'prompt': prompt, 'seed': seed, 'prefix': f'mytools-video/{job}/frame'}
        for index, name in enumerate(uploaded):
            bindings['reference' + str(index)] = name
        if control_path is not None and 'control' in value['bindings']:
            bindings['control'] = comfy.upload_file(base, control_path, f'mytools-{job}-control.mp4')
        if mask_path is not None and 'mask' in value['bindings']:
            bindings['mask'] = comfy.upload_file(base, mask_path, f'mytools-{job}-mask.mp4')
        graph = wf.bind(value, bindings)
        prompt_id, images, peak = await_frames(base, graph, wf.output_node(value), cleanup, FRAMES,
                                              int(os.environ.get('VIDEO_INFERENCE_TIMEOUT_SECONDS', 2400)))
        inference_ms = int((time.monotonic() - started) * 1000)
        paths = collect_frames(images, Path(os.environ['VIDEO_COMFY_OUTPUT_DIR']), FRAMES)
        restore = manifest['padRestoreFilter']
        encode_video(paths, output / 'video.mp4', work, restore)
        write_cover(paths[0], output / 'cover.png', restore)
        for path in paths:
            path.unlink(missing_ok=True)
    # 预处理中间产物即时清理；保留目录本身便于失败复查。
    for leftover in work.iterdir():
        leftover.unlink(missing_ok=True)
    result = {
        'frames': FRAMES, 'fps': FPS, 'width': width, 'height': height,
        # 49 帧 @16fps = 3062.5ms，四舍五入到毫秒（不用 round 的银行家舍入，避免少报时长）。
        'durationMs': (FRAMES * 1000 + FPS // 2) // FPS,
        'workflowRevision': value['revision'],
        'controlSha256': manifest['controlSha256'],
        'outputSha256': vc.digest(output / 'video.mp4'),
        'coverSha256': vc.digest(output / 'cover.png'),
        'promptId': prompt_id,
        'inferenceMillis': inference_ms,
        'resourcePeak': {'gpuPeakMiB': peak, 'memoryAvailableMiB': memory_available_mib()},
    }
    for key in ('referenceFill', 'fillBlend', 'sourceQuality', 'padRects'):
        if key in manifest:
            result[key] = manifest[key]
    return result


def main():
    """执行已签发任务，并只输出稳定错误码。"""
    try:
        context = json.loads(Path(os.environ['TASK_CONTEXT_FILE']).read_text())
        result = generate(context['parameters'])
        Path(os.environ['TASK_RESULT_FILE']).write_text(json.dumps(result))
    except Rejected as error:
        from mytools_task_sdk.errors import write_task_error
        write_task_error(error.code, 'PERMANENT', error.code)
        raise SystemExit(1)
    except Exception:
        from mytools_task_sdk.errors import write_task_error
        # 结果文件只写稳定错误码；栈单独打到 stderr，交给执行器日志，便于事后定位。
        traceback.print_exc()
        write_task_error('VIDEO_EXECUTION_FAILED', 'PERMANENT', 'VIDEO_EXECUTION_FAILED')
        raise SystemExit(1)


if __name__ == '__main__':
    main()
