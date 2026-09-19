#!/usr/bin/env python3
"""生成验收用的合成运动素材。

验收矩阵要求判断"原动作是否保留"，因此素材必须带有可核对的地面真值：一个匀速右移的方块
和一个静止方块。合成素材可复现、不含用户私有内容，运动位移可精确校验；它不是真实拍摄
素材，画质类结论不能只依赖它。

另有一个基于仓库内验收照片的匀速横移素材：合成几何体的边缘过于稀疏（边缘控制信号平均亮度
只有 0.0005），不适合评估边缘/结构控制；横移照片能提供足够密的纹理与边缘。
"""

import argparse
from pathlib import Path
import subprocess
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from prepare_control import encode

BACKGROUND = (30, 40, 54)
PANEL = (44, 58, 78)
MOVING = (255, 122, 26)
STATIC = (48, 192, 255)
MOVING_BOX = 120
STATIC_BOX = 90
MOVING_START_X = 60
MOVING_SPEED = 90.0
# 横移素材先放大到目标宽度的该倍数，再逐帧裁窗，保证有足够的横向位移空间。
PAN_WIDEN = 1.6


def wide_frame(path, width, height):
    """把源图等比缩放到加宽画布，便于后续逐帧裁窗。"""
    wide = int(round(width * PAN_WIDEN))
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-frames:v', '1',
                          '-vf', f'scale={wide}:{height}:force_original_aspect_ratio=increase,'
                                 f'crop={wide}:{height}',
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    frame_bytes = wide * height * 3
    if len(raw) != frame_bytes:
        raise ValueError('VIDEO_PAN_SOURCE_INVALID')
    return np.frombuffer(raw, dtype=np.uint8).reshape(height, wide, 3)


def build_pan_frames(path, frames, width, height):
    """从静图生成匀速横移镜头，位移可核对的整数像素步进。"""
    canvas = wide_frame(path, width, height)
    span = canvas.shape[1] - width
    if span <= 0:
        raise ValueError('VIDEO_PAN_SOURCE_TOO_NARROW')
    result = []
    for index in range(frames):
        left = int(round(span * index / max(1, frames - 1)))
        result.append(canvas[:, left:left + width])
    return np.stack(result)


def build_frames(frames, fps, width, height):
    """按固定速度生成右移方块与静止方块，返回 (T, H, W, 3) uint8。"""
    canvas = []
    for index in range(frames):
        frame = np.full((height, width, 3), BACKGROUND, dtype=np.uint8)
        frame[40:height - 40, 40:width - 40] = PANEL
        # 位移按帧序号线性推进，便于用首末帧位置核对运动是否保留。
        left = int(round(MOVING_START_X + MOVING_SPEED * index / fps))
        frame[210:210 + MOVING_BOX, left:left + MOVING_BOX] = MOVING
        frame[70:70 + STATIC_BOX, width - 152:width - 62] = STATIC
        canvas.append(frame)
    return np.stack(canvas)


def main():
    """写出素材视频并打印运动地面真值。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--pan-image', help='给出静图时生成匀速横移素材，否则生成方块运动素材')
    parser.add_argument('--frames', type=int, default=49)
    parser.add_argument('--fps', type=int, default=16)
    parser.add_argument('--width', type=int, default=832)
    parser.add_argument('--height', type=int, default=480)
    args = parser.parse_args()
    if args.pan_image:
        frames = build_pan_frames(args.pan_image, args.frames, args.width, args.height)
        encode(args.output, frames, args.fps)
        print(f'frames={args.frames} pan={args.width}->{args.width} span={(int(round(args.width * (PAN_WIDEN - 1))))}px',
              flush=True)
        return
    frames = build_frames(args.frames, args.fps, args.width, args.height)
    encode(args.output, frames, args.fps)
    last_x = MOVING_START_X + MOVING_SPEED * (args.frames - 1) / args.fps
    print(f'frames={args.frames} movingX={MOVING_START_X}->{last_x:.1f} staticBox=right', flush=True)


if __name__ == '__main__':
    main()
