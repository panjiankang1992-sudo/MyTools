#!/usr/bin/env python3
"""把分镜各镜头的产物按顺序拼接，并核对顺序、帧数与总时长。

分镜是"逐镜串行生成后拼接"，不是一次多图条件推理。拼接必须能回答三个验收判据：
每个镜头都真实存在且可解码（不能有失败镜头被掩盖）、顺序与请求一致（用哈希对账）、
总时长等于各镜头之和（不能凭空增减或错算转场）。

兼容性检查与总时长推导是纯函数，可脱离 ffmpeg 测试；实际拼接由 ffmpeg 完成并复测结果。
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def sha256(path):
    """计算文件哈希，用于对账镜头顺序与产物身份。"""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def probe(path):
    """读取单个镜头的流信息。"""
    document = json.loads(subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0',
         '-show_entries', 'stream=width,height,avg_frame_rate,nb_frames',
         '-show_entries', 'format=duration', '-of', 'json', str(path)],
        check=True, capture_output=True, text=True).stdout)
    stream = (document.get('streams') or [{}])[0]
    frames = int(stream.get('nb_frames') or 0)
    if frames <= 0:
        raise ValueError('VIDEO_SHOT_FRAMES_UNKNOWN:' + Path(path).name)
    return {'file': str(path), 'frames': frames, 'width': int(stream['width']), 'height': int(stream['height']),
            'fps': stream.get('avg_frame_rate'), 'durationSeconds': float(document['format']['duration']),
            'sha256': sha256(path)}


def check_compatible(shots):
    """所有镜头必须同尺寸同帧率，否则拼接会产生静默的缩放或丢帧。"""
    if not shots:
        raise ValueError('VIDEO_STORYBOARD_EMPTY')
    first = shots[0]
    for shot in shots:
        if (shot['width'], shot['height'], shot['fps']) != (first['width'], first['height'], first['fps']):
            raise ValueError('VIDEO_SHOT_FORMAT_MISMATCH:' + Path(shot['file']).name)
    return True


def expected_totals(shots, transition_seconds=0.0):
    """按各镜头实测时长推导拼接结果应有的总时长与总帧数。

    交叉溶解会让相邻两段重叠，因此总时长 = 各段之和 − 转场时长 ×（段数 − 1）。
    这不是"看起来差不多"，而是可以直接核对的等式。
    """
    if transition_seconds < 0:
        raise ValueError('VIDEO_TRANSITION_INVALID')
    durations = [shot['durationSeconds'] for shot in shots]
    overlap = transition_seconds * (len(shots) - 1)
    if transition_seconds and transition_seconds >= min(durations):
        raise ValueError('VIDEO_TRANSITION_TOO_LONG')
    total = sum(durations) - overlap
    fps = float(shots[0]['fps'].split('/')[0])
    return {'frames': int(round(total * fps)), 'durationSeconds': round(total, 4),
            'transitionSeconds': round(transition_seconds, 4)}


def transition_offsets(shots, transition_seconds):
    """返回每一段交叉溶解的起始偏移：第 k 段用 sum(D[0..k-1]) − k × 转场时长。"""
    offsets = []
    elapsed = 0.0
    for index in range(1, len(shots)):
        elapsed += shots[index - 1]['durationSeconds']
        offsets.append(round(elapsed - index * transition_seconds, 4))
    return offsets


def verify(shots, result, transition_seconds=0.0):
    """核对拼接结果与各镜头之和一致：帧数容差 1 帧，时长容差 1.5 帧。"""
    check_compatible(shots)
    expected = expected_totals(shots, transition_seconds)
    tolerance = max(0.05, 1.5 / float(result['fps'].split('/')[0]))
    problems = []
    if abs(result['frames'] - expected['frames']) > 1:
        problems.append(f"frames {result['frames']} != {expected['frames']}")
    if abs(result['durationSeconds'] - expected['durationSeconds']) > tolerance:
        problems.append(f"duration {result['durationSeconds']} != {expected['durationSeconds']}")
    if problems:
        raise ValueError('VIDEO_STORYBOARD_TOTAL_MISMATCH:' + ';'.join(problems))
    return expected


def concat(shots, output, transition_seconds=0.0):
    """按给定顺序拼接，重新编码以保证帧数与时长可核对。

    转场为 0 时用 concat 解复用器硬切；大于 0 时用 xfade 做交叉溶解，并按公式计算每段偏移。
    """
    listing = Path(output).with_suffix('.txt')
    if not transition_seconds:
        listing.write_text(''.join(f"file '{Path(shot['file']).resolve()}'\n" for shot in shots))
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', str(listing),
                        '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(output)],
                       check=True)
        return
    command = ['ffmpeg', '-v', 'error', '-y']
    for shot in shots:
        command += ['-i', str(Path(shot['file']).resolve())]
    chain = []
    offsets = transition_offsets(shots, transition_seconds)
    source = '[0:v]'
    for index, offset in enumerate(offsets, start=1):
        target = f'[v{index}]'
        chain.append(f"{source}[{index}:v]xfade=transition=fade:duration={transition_seconds}:"
                     f"offset={offset}{target}")
        source = target
    command += ['-filter_complex', ';'.join(chain), '-map', source,
                '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(output)]
    subprocess.run(command, check=True)


def main():
    """拼接指定镜头并写出可归档的核对清单。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--shot', action='append', required=True, help='按播放顺序给出，可重复')
    parser.add_argument('--output', required=True)
    parser.add_argument('--manifest')
    parser.add_argument('--transition-seconds', type=float, default=0.0,
                        help='相邻镜头之间的交叉溶解时长；0 表示硬切')
    args = parser.parse_args()
    shots = [probe(path) for path in args.shot]
    # 先校验再拼接：失败镜头缺失或顺序不齐必须在这里暴露。
    check_compatible(shots)
    expected = expected_totals(shots, args.transition_seconds)
    concat(shots, args.output, args.transition_seconds)
    result = probe(args.output)
    expected = verify(shots, result, args.transition_seconds)
    manifest = {'shots': shots, 'expected': expected,
                'result': {key: result[key] for key in ('file', 'frames', 'width', 'height', 'fps',
                                                        'durationSeconds', 'sha256')},
                'transitionSeconds': args.transition_seconds,
                'transitionOffsets': transition_offsets(shots, args.transition_seconds)}
    if args.manifest:
        Path(args.manifest).write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == '__main__':
    main()
