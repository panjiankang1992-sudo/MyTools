#!/usr/bin/env python3
"""对生成的视频计算客观指标，辅助人工验收评分。

这些指标只能发现"静止复制帧、全黑帧、画面塌陷成纯色"这类硬故障，不能替代人工判断
指令遵循、主体保持与伪影。评分仍以 acceptance.md 的人工评分为准。

注意：绝对阈值与内容强相关。合成素材（大面积纯色 + 少量几何体）的平均像素梯度天然很低，
用固定阈值判定"纯色塌陷"会产生误报，因此对视频改编类用例必须提供 `--baseline` 源视频，
以相对比值判断结构、运动与亮度是否被保留。
"""

import argparse
import json
from pathlib import Path
import subprocess

import numpy as np

# 归一化到 0..1 后的绝对阈值，仅在缺少基线时作为粗略提示，不作为验收结论。
FROZEN_THRESHOLD = 0.004
BLACK_THRESHOLD = 0.02
# 平均亮度低于阈值且峰值像素也极低，才算空白帧；只有平均亮度低而存在高光时只是偏暗。
BLANK_PEAK_THRESHOLD = 0.05
WHITE_THRESHOLD = 0.98
FLAT_DETAIL_THRESHOLD = 0.002


def decode_frames(path, width, height):
    """把视频解码为 (T, H, W, 3) 的 0..1 浮点帧，固定尺寸避免缩放干扰指标。"""
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path),
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    frame_bytes = width * height * 3
    if not raw or len(raw) % frame_bytes:
        raise ValueError('VIDEO_DECODE_INVALID')
    frames = np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3)
    return frames.astype(np.float32) / 255.0


def spatial_detail(frame):
    """用相邻像素差的平均值衡量画面细节，用于发现塌陷成纯色或严重模糊。"""
    horizontal = np.abs(np.diff(frame, axis=1)).mean()
    vertical = np.abs(np.diff(frame, axis=0)).mean()
    return float((horizontal + vertical) / 2)


def summarize(frames, fps, baseline=None):
    """计算运动、冻结帧、黑白帧与细节指标；提供基线时给出相对比值。"""
    motion = np.abs(np.diff(frames, axis=0)).mean(axis=(1, 2, 3)) if frames.shape[0] > 1 else np.zeros(1, dtype=np.float32)
    brightness = frames.mean(axis=(1, 2, 3))
    peak = frames.max(axis=(1, 2, 3))
    detail = np.array([spatial_detail(frame) for frame in frames], dtype=np.float32)
    frozen = int(np.count_nonzero(motion < FROZEN_THRESHOLD))
    dark = brightness < BLACK_THRESHOLD
    # "全黑/空白帧"与"很暗但有内容"是两种不同故障：前者是严重失败判据，后者只是偏暗。
    # 只按平均亮度判定会把带高光的暗帧误报成全黑，因此必须同时看峰值像素。
    blank = dark & (peak < BLANK_PEAK_THRESHOLD)
    summary = {
        'frames': int(frames.shape[0]),
        'fps': fps,
        'durationSeconds': round(frames.shape[0] / fps, 4),
        'resolution': [int(frames.shape[2]), int(frames.shape[1])],
        'motion': {'min': round(float(motion.min()), 6), 'mean': round(float(motion.mean()), 6),
                   'max': round(float(motion.max()), 6), 'belowAbsoluteThreshold': frozen},
        'brightness': {'min': round(float(brightness.min()), 4), 'max': round(float(brightness.max()), 4),
                       'mean': round(float(brightness.mean()), 4)},
        'blankFrames': int(np.count_nonzero(blank)),
        'veryDarkFrames': int(np.count_nonzero(dark)),
        'darkFrameIndices': [int(index) for index in np.nonzero(dark)[0]],
        'whiteFrames': int(np.count_nonzero(brightness > WHITE_THRESHOLD)),
        'detail': {'min': round(float(detail.min()), 6), 'mean': round(float(detail.mean()), 6)},
        'belowAbsoluteDetailThreshold': int(np.count_nonzero(detail < FLAT_DETAIL_THRESHOLD)),
        'firstLastDifference': round(float(np.abs(frames[0] - frames[-1]).mean()), 6),
        'thresholds': {'frozenMotion': FROZEN_THRESHOLD, 'darkBrightness': BLACK_THRESHOLD,
                       'blankPeak': BLANK_PEAK_THRESHOLD, 'flatDetail': FLAT_DETAIL_THRESHOLD,
                       'note': '绝对阈值与内容相关；合成素材梯度低，必须用 relativeToBaseline 判断'},
    }
    if baseline is not None:
        summary['relativeToBaseline'] = {
            'motionRatio': round(float(motion.mean() / baseline['motion']['mean']), 3) if baseline['motion']['mean'] else None,
            'detailRatio': round(float(detail.mean() / baseline['detail']['mean']), 3) if baseline['detail']['mean'] else None,
            'brightnessRatio': round(float(brightness.mean() / baseline['brightness']['mean']), 3) if baseline['brightness']['mean'] else None,
        }
    return summary


def load_anchor(path, width, height):
    """把锚点图按与预处理一致的方式（等比缩放 + 白边填充）转成帧，便于与输出帧直接比较。"""
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-frames:v', '1',
                          '-vf', f'scale={width}:{height}:force_original_aspect_ratio=decrease,'
                                 f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=white',
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    frame_bytes = width * height * 3
    if len(raw) != frame_bytes:
        raise ValueError('VIDEO_ANCHOR_DECODE_INVALID')
    return np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 3).astype(np.float32) / 255.0


def compare_anchors(frames, anchors):
    """把指定锚点图与输出首帧/末帧比较，用于判断首帧是否保留、尾帧是否被忽略。

    返回每个锚点的平均绝对差；数值越小表示越接近。这只是客观相似度，不能替代人工判断
    "构图与主体是否合理"，但足以发现"尾帧被完全忽略"这类硬故障。
    """
    height, width = frames.shape[1:3]
    results = {}
    for position, paths in anchors.items():
        target = frames[0] if position == 'first' else frames[-1]
        results[position] = {Path(path).name: round(float(np.abs(target - load_anchor(path, width, height)).mean()), 5)
                             for path in paths}
        results[position + 'Closest'] = min(results[position], key=results[position].get)
    return results


def measure(path, fps, baseline=None, anchors=None):
    """按视频自身尺寸测量；提供基线时附带相对比值。"""
    probe = json.loads(subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
         'stream=width,height,nb_frames', '-of', 'json', str(path)],
        check=True, capture_output=True, text=True).stdout)['streams'][0]
    frames = decode_frames(path, int(probe['width']), int(probe['height']))
    summary = summarize(frames, fps, baseline)
    if anchors:
        summary['anchors'] = compare_anchors(frames, anchors)
    return summary


def main():
    """对给定视频写出指标 JSON，并打印摘要。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('video')
    parser.add_argument('--fps', type=int, default=16)
    parser.add_argument('--baseline', help='同一用例的源视频，用于给出相对比值')
    parser.add_argument('--first-anchor', action='append', default=[],
                        help='应与输出首帧接近的参考图，可重复')
    parser.add_argument('--last-anchor', action='append', default=[],
                        help='应与输出末帧接近的参考图，可重复')
    parser.add_argument('--output')
    args = parser.parse_args()
    baseline = measure(args.baseline, args.fps) if args.baseline else None
    anchors = {}
    if args.first_anchor:
        anchors['first'] = args.first_anchor
    if args.last_anchor:
        anchors['last'] = args.last_anchor
    summary = measure(args.video, args.fps, baseline, anchors or None)
    summary['source'] = str(args.video)
    if args.baseline:
        summary['baseline'] = str(args.baseline)
    if args.output:
        Path(args.output).write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == '__main__':
    main()
