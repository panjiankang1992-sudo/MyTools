"""按 VACE 官方语义生成控制视频与掩码，作为固定工作流的输入文件。

掩码约定取自官方 `vace/annotators/frameref.py`：参考帧（首帧/尾帧等要保留的帧）使用原图且
掩码为 0，其余帧填中性灰且掩码为 255（表示需要生成）。灰度信号取自官方
`vace/annotators/gray.py` 的 BGR2GRAY 亮度语义，不能用单通道提取冒充灰度。

与开发期工具 `tools/prepare_control.py` 是同源实现：两边用同一套常量与几何语义，并由
`tests/test_worker.py` 的漂移用例逐帧比对，避免生产包与证据工具产生分歧。
"""

import hashlib
import subprocess
from pathlib import Path

import numpy as np

# 与官方 frameref 的 REF_COLOR 一致；uint8 取整为 128，对 0.5 中性灰的偏差为 0.002。
REFERENCE_FILL = 127.5
GENERATED_MASK = 255
PRESERVED_MASK = 0
# 补边颜色：白边会被参考帧保留并整段留在成片里，因此统一使用中性灰。
PAD_COLOR_HEX = '0x808080'
# 补边的 RGB 分量与 drawbox 颜色，二者必须同源；PAD_TOLERANCE 吸收编解码取整误差。
PAD_COLOR_RGB = (128, 128, 128)
PAD_DRAW_COLOR = '0x808080'
PAD_TOLERANCE = 4
# 单图首帧的控制强度：在灰与参考图之间插值。
# P0 的 blend 扫描（p0-window-20260914T112215Z/blend-sweep.txt）显示：
#   α=0.25 帧均亮度 0.677（源 0.714，偏离 −0.037）、首末差 0.124；
#   α=0.50 帧均亮度 0.701（−0.013）、首末差 0.061，运动量几乎不变；
#   α=0.75 与 repeat 首末差 0.018/0.009，判定 FROZEN。
# 但 α=0.25 的颜色约束太弱，白底素材的**内容区**会在成片里漂成蓝色（实测 B 通道 +54~+118），
# 因此改为 0.50：颜色/亮度保真明显更好，运动仍在可接受区间。可用 VIDEO_FILL_BLEND 覆盖。
DEFAULT_FILL_BLEND = 0.50
# ITU-R 601 亮度权重，与 cv2.COLOR_BGR2GRAY 相同；本模块输入为 RGB，因此按 R/G/B 顺序应用。
LUMA = np.array([0.299, 0.587, 0.114], dtype=np.float32)
SIGNAL_FILTERS = {'raw': None, 'gray': None, 'edge': 'edgedetect=low=0.1:high=0.4'}
MAX_PIXELS = 16_000_000


def run_ffmpeg(args, expected_bytes=None, frame_bytes=None):
    """执行一次固定参数的 ffmpeg，并校验解码完整性。

    `expected_bytes` 用于已知精确帧数的场景（单张图片、固定长度几何），`frame_bytes` 用于
    长度可变但必须整帧对齐的场景（带裁剪窗口的视频）。传输中断会留下尺寸正常却缺数据块的
    文件，只有真正解码一次才能发现，因此这两种检查都不接受"非零但长度正常"的结果。
    """
    result = subprocess.run(args, capture_output=True, timeout=600, check=False)
    if result.returncode != 0:
        raise ValueError('VIDEO_INPUT_UNDECODABLE')
    if expected_bytes is not None and len(result.stdout) != expected_bytes:
        raise ValueError('VIDEO_INPUT_UNDECODABLE')
    if frame_bytes is not None and (len(result.stdout) == 0 or len(result.stdout) % frame_bytes):
        raise ValueError('VIDEO_INPUT_UNDECODABLE')
    return result.stdout


def fit_frames(frames, count):
    """把帧序列精确调整到 count 帧：多余截断，不足重复末帧。"""
    if frames.shape[0] == 0:
        raise ValueError('VIDEO_SOURCE_EMPTY')
    if frames.shape[0] >= count:
        return frames[:count]
    padding = np.repeat(frames[-1:], count - frames.shape[0], axis=0)
    return np.concatenate([frames, padding], axis=0)


def reference_frames(frames, indices, fill_mode='blend', blend=DEFAULT_FILL_BLEND):
    """按官方 frameref 语义生成控制帧与掩码帧，返回 (control, mask) 两组浮点帧。

    `fill_mode` 只影响"非参考帧"的控制内容，掩码始终不变（参考帧 0、其余 255）：
    `grey` 填 127.5 灰（官方默认）、`repeat` 填参考图本身、`blend` 在两者之间插值。
    实测灰填充会在部分种子下塌陷出全黑帧，直接填参考图会把画面压成近乎静止，
    因此生产固定使用 `blend` 的 α=0.25 档。
    """
    if fill_mode not in ('grey', 'repeat', 'blend'):
        raise ValueError('VIDEO_FILL_MODE_INVALID')
    if fill_mode == 'blend' and not 0.0 < blend < 1.0:
        raise ValueError('VIDEO_BLEND_OUT_OF_RANGE')
    height, width = frames.shape[1:3]
    preserved = set(indices)
    if not preserved:
        raise ValueError('VIDEO_REFERENCE_INDEX_REQUIRED')
    source = frames[min(preserved)].astype(np.float32)
    if fill_mode == 'grey':
        fill = np.full((height, width, 3), REFERENCE_FILL, dtype=np.float32)
    elif fill_mode == 'repeat':
        fill = source
    else:
        grey = np.full((height, width, 3), REFERENCE_FILL, dtype=np.float32)
        fill = grey * (1.0 - blend) + source * blend
    control, mask = [], []
    for index in range(frames.shape[0]):
        if index in preserved:
            control.append(frames[index].astype(np.float32))
            mask.append(np.full((height, width), PRESERVED_MASK, dtype=np.float32))
        else:
            control.append(fill.copy())
            mask.append(np.full((height, width), GENERATED_MASK, dtype=np.float32))
    return np.stack(control), np.stack(mask)


def luma(frames):
    """按 ITU-R 601 计算亮度并复制到三通道，等价于官方 gray.py 的灰度信号。"""
    gray = np.tensordot(frames.astype(np.float32), LUMA, axes=([-1], [0]))
    return np.repeat(gray[..., None], 3, axis=-1)


def source_quality(frames):
    """量化源片是否适合结构重绘：整体亮度与空间细节（均为 0..1 口径）。"""
    sample = frames.astype(np.float32) / 255.0
    brightness = float(sample.mean())
    detail = float(np.abs(np.diff(sample, axis=2)).mean())
    return {'brightness': round(brightness, 4), 'detail': round(detail, 6)}


def require_restyle_source(frames, min_brightness=0.30, min_detail=0.003):
    """结构重绘的准入检查；不满足就拒绝，而不是产出一段接近全黑的视频。"""
    quality = source_quality(frames)
    if quality['brightness'] < min_brightness:
        raise ValueError('VIDEO_SOURCE_TOO_DARK:' + str(quality['brightness']))
    if quality['detail'] < min_detail:
        raise ValueError('VIDEO_SOURCE_TOO_FLAT:' + str(quality['detail']))
    return quality


def parse_rect(text):
    """解析 `x:y:w:h` 形式的矩形，供局部修改掩码使用。"""
    parts = text.split(':')
    if len(parts) != 4:
        raise ValueError('VIDEO_MASK_RECT_INVALID')
    try:
        x, y, width, height = (int(part) for part in parts)
    except ValueError as error:
        raise ValueError('VIDEO_MASK_RECT_INVALID') from error
    if x < 0 or y < 0 or width <= 0 or height <= 0:
        raise ValueError('VIDEO_MASK_RECT_INVALID')
    return x, y, width, height


def mask_from_rects(frames, rects):
    """生成掩码帧：矩形内为 255（生成），矩形外为 0（保留），与官方约定一致。"""
    height, width = frames.shape[1:3]
    mask = np.zeros((height, width), dtype=np.float32)
    for x, y, rect_width, rect_height in rects:
        if x + rect_width > width or y + rect_height > height:
            raise ValueError('VIDEO_MASK_RECT_OUT_OF_BOUNDS')
        mask[y:y + rect_height, x:x + rect_width] = GENERATED_MASK
    return np.repeat(mask[None, ...], frames.shape[0], axis=0)


def decode(path, width, height, signal='raw', pad_color=PAD_COLOR_HEX, start_ms=0, limit=None):
    """解码源视频并按目标尺寸等比缩放加中性边填充，返回 (T, H, W, 3) uint8。

    `start_ms` 与 `limit` 对应业务层的裁剪窗口：只解码需要的帧，避免整片解码后再截断。
    """
    chain = [f'scale={width}:{height}:force_original_aspect_ratio=decrease',
             f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color={pad_color}']
    if SIGNAL_FILTERS[signal]:
        chain.append(SIGNAL_FILTERS[signal])
    args = ['ffmpeg', '-v', 'error', '-i', str(path)]
    if start_ms > 0:
        # 放在输入之后是帧精确的定位；片段窗口最多 5 秒，代价可接受。
        args += ['-ss', f'{start_ms / 1000:.3f}']
    args += ['-vf', ','.join(chain)]
    if limit:
        args += ['-frames:v', str(limit)]
    args += ['-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = run_ffmpeg(args, frame_bytes=width * height * 3)
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3).copy()


def decode_image(path, width, height, pad_color=PAD_COLOR_HEX):
    """读取单张图片并按目标尺寸等比缩放加中性边填充，返回 (1, H, W, 3) uint8。"""
    raw = run_ffmpeg(['ffmpeg', '-v', 'error', '-i', str(path), '-frames:v', '1',
                      '-vf', f'scale={width}:{height}:force_original_aspect_ratio=decrease,'
                             f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color={pad_color}',
                      '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], expected_bytes=width * height * 3)
    return np.frombuffer(raw, dtype=np.uint8).reshape(1, height, width, 3).copy()


def decode_mask(path, width, height, frames, start_ms=0):
    """把用户提供的黑白蒙版视频解码为 0/255 掩码帧；掩码取红通道。"""
    args = ['ffmpeg', '-v', 'error', '-i', str(path)]
    if start_ms > 0:
        args += ['-ss', f'{start_ms / 1000:.3f}']
    args += ['-vf', f'scale={width}:{height}', '-frames:v', str(frames),
             '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = run_ffmpeg(args, frame_bytes=width * height * 3)
    frames_array = fit_frames(np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3), frames)
    # 二值化：任何非黑像素都视为需要生成，避免抗锯齿灰边被当成保留区。
    return np.where(frames_array[..., 0] > 0, GENERATED_MASK, PRESERVED_MASK).astype(np.float32)


def detect_pad_rects(frame):
    """从控制帧量出补边矩形，返回 (left, right, top, bottom)。

    补边是 ffmpeg pad 出来的纯色区域，整列/整行像素完全相同；而模型会把成片里这两条边改成
    任意颜色（实测同一素材一次偏蓝一次偏黄）。所以几何一律以"控制帧"为准，出片后再按同一
    几何还原，避免依赖 ffmpeg 的取整细节。
    """
    height, width = frame.shape[:2]
    fill = np.array(PAD_COLOR_RGB, dtype=np.int16)

    def column_is_pad(x):
        return bool(np.all(np.abs(frame[:, x, :].astype(np.int16) - fill) <= PAD_TOLERANCE))

    def row_is_pad(y):
        return bool(np.all(np.abs(frame[y, :, :].astype(np.int16) - fill) <= PAD_TOLERANCE))

    # 每边最多认到一半，且对边互不重叠：整幅纯色时不会把整张图当成补边。
    left = 0
    while left < width // 2 and column_is_pad(left):
        left += 1
    right = 0
    while right < width - left and column_is_pad(width - 1 - right):
        right += 1
    top = 0
    while top < height // 2 and row_is_pad(top):
        top += 1
    bottom = 0
    while bottom < height - top and row_is_pad(height - 1 - bottom):
        bottom += 1
    return left, right, top, bottom


def restore_filter(rects, width, height):
    """把补边还原为中性灰的 ffmpeg 滤镜；没有补边时返回 None。

    成片与封面共用这一套滤镜，保证用户看到的封面和图里那条边一致。
    """
    left, right, top, bottom = rects
    boxes = []
    if left > 0:
        boxes.append(f'drawbox=x=0:y=0:w={left}:h={height}:color={PAD_DRAW_COLOR}@1:t=fill')
    if right > 0:
        boxes.append(f'drawbox=x={width - right}:y=0:w={right}:h={height}:color={PAD_DRAW_COLOR}@1:t=fill')
    if top > 0:
        boxes.append(f'drawbox=x=0:y=0:w={width}:h={top}:color={PAD_DRAW_COLOR}@1:t=fill')
    if bottom > 0:
        boxes.append(f'drawbox=x=0:y={height - bottom}:w={width}:h={bottom}:color={PAD_DRAW_COLOR}@1:t=fill')
    return ','.join(boxes) if boxes else None


def encode(path, frames, fps):
    """以 H.264/yuv420p 写出固定尺寸视频，便于 LoadVideo 稳定解码。"""
    height, width = frames.shape[1:3]
    payload = np.rint(frames).clip(0, 255).astype(np.uint8).tobytes()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                    '-s', f'{width}x{height}', '-r', str(fps), '-i', '-',
                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                    str(path)], input=payload, check=True, timeout=600)


def digest(path):
    """记录产物的 SHA-256，便于 evidence 复核。"""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
