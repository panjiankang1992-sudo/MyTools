#!/usr/bin/env python3
"""按 VACE 官方语义生成控制视频与掩码，作为固定工作流的输入文件。

掩码约定取自官方 `vace/annotators/frameref.py`（提交 48eb44f1）：参考帧（首帧/尾帧等要保留
的帧）使用原图且掩码为 0，其余帧填充 127.5 灰且掩码为 255（表示需要生成）。灰度信号取自
官方 `vace/annotators/gray.py` 的 BGR2GRAY 亮度语义，不能用单通道提取冒充灰度。

只做 CPU 归一化与信号提取，不加载任何模型，因此不需要 GPU 租约；结果文件与参数哈希都写入
manifest，供 evidence 归档。
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import numpy as np

# 与官方 frameref 的 REF_COLOR 一致；uint8 取整为 128，对 0.5 中性灰的偏差为 0.002。
REFERENCE_FILL = 127.5
GENERATED_MASK = 255
PRESERVED_MASK = 0
# 等比缩放后的补边颜色。实测白边（#FFFFFF）会被参考帧保留并整段留在成片左右两侧
# （1024×1024 方图塞进 832×480 时左右各 60 列近乎纯白，输出边缘长期维持在 0.94–0.98），
# 因此改为与 REFERENCE_FILL 一致的中性灰，让补边融入 VACE 自身的未知区域先验。
PAD_COLOR = 128
PAD_COLOR_HEX = '0x808080'
# ITU-R 601 亮度权重，与 cv2.COLOR_BGR2GRAY 相同；本脚本输入为 RGB，因此按 R/G/B 顺序应用。
LUMA = np.array([0.299, 0.587, 0.114], dtype=np.float32)
SIGNAL_FILTERS = {'raw': None, 'gray': None, 'edge': 'edgedetect=low=0.1:high=0.4'}


def fit_frames(frames, count):
    """把帧序列精确调整到 count 帧：多余截断，不足重复末帧。"""
    if frames.shape[0] == 0:
        raise ValueError('VIDEO_SOURCE_EMPTY')
    if frames.shape[0] >= count:
        return frames[:count]
    padding = np.repeat(frames[-1:], count - frames.shape[0], axis=0)
    return np.concatenate([frames, padding], axis=0)


def reference_frames(frames, indices, fill_mode='grey', blend=0.0):
    """按官方 frameref 语义生成控制帧与掩码帧，返回 (control, mask) 两组浮点帧。

    `fill_mode` 控制"非参考帧"的控制内容，掩码始终不变（参考帧 0、其余 255）：
    - `grey`（官方默认）填 127.5 灰，生成区间几乎没有内容先验；
    - `repeat` 填参考图本身，内容先验最强；
    - `blend` 在灰与参考图之间线性插值，越接近 1 越强，用来找控制强度的中间档。

    实测背景：灰填充会在部分种子下塌陷出全黑帧，而直接填参考图会把画面压成近乎静止，
    因此需要可调的控制强度，而不是二选一。
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
    """量化源片是否适合结构重绘：整体亮度与空间细节。

    实测：暗且低纹理的源片（亮度 0.23、细节 0.0007）会让灰度重绘把背景压成接近全黑
    （帧均值 0.011–0.029）；而正常纹理源片（亮度 0.42、细节 0.011）结果正常。
    这里给出这两个量，供调用方决定是否拒绝该素材。
    """
    # 统一到 0..1，阈值才与实测口径一致（原始像素是 0..255）。
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


def decode(path, width, height, signal='raw', pad_color=PAD_COLOR_HEX):
    """解码源视频并按目标尺寸等比缩放加中性边填充，返回 (T, H, W, 3) uint8。"""
    chain = [f'scale={width}:{height}:force_original_aspect_ratio=decrease',
             f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color={pad_color}']
    if SIGNAL_FILTERS[signal]:
        chain.append(SIGNAL_FILTERS[signal])
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-vf', ','.join(chain),
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    frame_bytes = width * height * 3
    if len(raw) % frame_bytes:
        raise ValueError('VIDEO_DECODE_TRUNCATED')
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3).copy()


def decode_image(path, width, height, pad_color=PAD_COLOR_HEX):
    """读取单张图片并按目标尺寸等比缩放加中性边填充，返回 (1, H, W, 3) uint8。"""
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-frames:v', '1',
                          '-vf', f'scale={width}:{height}:force_original_aspect_ratio=decrease,'
                                 f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color={pad_color}',
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(1, height, width, 3).copy()


def encode(path, frames, fps):
    """以 H.264/yuv420p 写出固定尺寸视频，便于 LoadVideo 稳定解码。"""
    height, width = frames.shape[1:3]
    payload = np.rint(frames).clip(0, 255).astype(np.uint8).tobytes()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                    '-s', f'{width}x{height}', '-r', str(fps), '-i', '-',
                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                    str(path)], input=payload, check=True)


def digest(path):
    """记录产物的 SHA-256，便于 evidence 复核。"""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build(args):
    """按模式生成控制信号；参考帧模式额外生成掩码。"""
    pad_color = getattr(args, 'pad_color', PAD_COLOR_HEX)
    mask_indices = None
    if args.mode == 'firstframe':
        # 第 0 帧是原图，其余帧未知；尾帧位置按帧序号取，不能按帧率换算时间。
        mask_indices = [0]
    elif args.mode == 'firstlastframe':
        mask_indices = [0, args.frames - 1]
    if args.mode in ('firstframe', 'firstlastframe'):
        expected = 1 if args.mode == 'firstframe' else 2
        if len(args.image) != expected:
            raise ValueError('VIDEO_SOURCE_IMAGE_COUNT_INVALID')
        frames = np.concatenate([decode_image(path, args.width, args.height, pad_color) for path in args.image])
        frames = fit_frames(frames, args.frames)
        control, mask = reference_frames(frames, mask_indices, args.reference_fill, args.fill_blend)
    elif args.mode == 'restyle':
        if not args.video:
            raise ValueError('VIDEO_SOURCE_REQUIRED')
        if args.signal not in ('gray', 'edge'):
            # 禁止把原 RGB 直接当控制信号冒充灰度或边缘。
            raise ValueError('VIDEO_CONTROL_SIGNAL_REQUIRED')
        frames = fit_frames(decode(args.video, args.width, args.height, args.signal, pad_color), args.frames)
        # 先做源片准入：暗且低纹理的素材会让重绘把背景压黑，直接拒绝而不是照跑。
        quality = require_restyle_source(frames) if args.check_source else source_quality(frames)
        control = luma(frames) if args.signal == 'gray' else frames.astype(np.float32)
        mask = None
    elif args.mode == 'masked':
        # 局部修改：控制信号是原片本身，掩码圈定要重新生成的区域，其余必须保留。
        if not args.video or not args.mask_rect:
            raise ValueError('VIDEO_SOURCE_REQUIRED')
        frames = fit_frames(decode(args.video, args.width, args.height, 'raw', pad_color), args.frames)
        control = frames.astype(np.float32)
        mask = mask_from_rects(frames, [parse_rect(text) for text in args.mask_rect])
    else:
        raise ValueError('VIDEO_PREPROCESS_MODE_UNSUPPORTED')
    return control, mask, mask_indices


def main():
    """写出控制视频、（可选）掩码视频与 manifest。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', required=True, choices=['firstframe', 'firstlastframe', 'restyle', 'masked'])
    parser.add_argument('--signal', default='raw', choices=sorted(SIGNAL_FILTERS))
    parser.add_argument('--no-source-check', dest='check_source', action='store_false',
                        help='跳过结构重绘的源片亮度/细节准入（仅用于复现历史用例）')
    parser.add_argument('--reference-fill', default='grey', choices=['grey', 'repeat', 'blend'],
                        help='frameref 模式里非参考帧的控制内容：grey 官方默认，repeat 填参考图，blend 插值')
    parser.add_argument('--fill-blend', type=float, default=0.0,
                        help='--reference-fill blend 时的插值比例，(0,1) 开区间')
    parser.add_argument('--mask-rect', action='append', default=[],
                        help='局部修改要重新生成的矩形区域，格式 x:y:w:h，可重复')
    parser.add_argument('--pad-color', default=PAD_COLOR_HEX,
                        help='等比缩放后的补边颜色，默认中性灰 0x808080；禁止使用白色')
    parser.add_argument('--image', action='append', default=[])
    parser.add_argument('--video')
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--frames', type=int, default=49)
    parser.add_argument('--fps', type=int, default=16)
    parser.add_argument('--width', type=int, default=832)
    parser.add_argument('--height', type=int, default=480)
    args = parser.parse_args()
    if args.frames not in (49, 81):
        raise ValueError('VIDEO_FRAMES_INVALID')
    folder = Path(args.output_dir)
    folder.mkdir(parents=True, exist_ok=True)
    control, mask, mask_indices = build(args)
    control_path = folder / 'control.mp4'
    encode(control_path, control, args.fps)
    manifest = {'mode': args.mode, 'signal': args.signal, 'frames': args.frames, 'fps': args.fps,
                'width': args.width, 'height': args.height, 'sourceImages': args.image,
                'sourceVideo': args.video, 'preservedFrameIndices': mask_indices,
                'referenceFill': args.reference_fill, 'fillBlend': args.fill_blend,
                'padColor': args.pad_color,
                'sourceQuality': quality,
                'controlSha256': digest(control_path)}
    if mask is not None:
        mask_path = folder / 'mask.mp4'
        # 单通道掩码复制到三通道，工作流按红通道还原为掩码。
        encode(mask_path, np.repeat(mask[..., None], 3, axis=-1), args.fps)
        manifest['maskSha256'] = digest(mask_path)
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    main()
