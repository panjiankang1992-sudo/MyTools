#!/usr/bin/env python3
"""把首帧模式的控制强度扫描列成一张表：控制强度 vs 全黑 / 亮度 / 运动 / 漂移。

判读目标不是"某个数字达标"，而是看两个极端之间是否存在"既不塌陷又确实在动"的中间档：
- 塌陷：出现空白帧（全黑）或亮度远低于源图；
- 静止：首末帧差异接近 0（源静图本身是 0）。
"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
IN = '/opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/input'
SWEEP = E + '/window-20260914T112215Z'
CASES = [
    ('still image', 'image', IN + '/p0-subject-a.png'),
    ('grey 0.502 @seed42', 'video', E + '/window-20260914T065550Z/run-01/output.mp4'),
    ('grey 0.502 @seed932', 'video', E + '/window-20260914T103811Z/run-01/output.mp4'),
    ('blend 0.552 (a=.25)', 'video', SWEEP + '/run-01/output.mp4'),
    ('blend 0.606 (a=.50)', 'video', SWEEP + '/run-02/output.mp4'),
    ('blend 0.664 (a=.75)', 'video', SWEEP + '/run-03/output.mp4'),
    ('repeat 0.720 @seed932', 'video', E + '/window-20260914T110655Z/run-01/output.mp4'),
]


def load(path, kind, w=832, h=480):
    if kind == 'image':
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-frames:v', '1', '-vf',
                              f'scale={w}:{h}:force_original_aspect_ratio=decrease,'
                              f'pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=white',
                              '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                             capture_output=True, check=True).stdout
        return np.frombuffer(raw, dtype=np.uint8).reshape(1, h, w, 3).astype(np.float32) / 255.0
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


print('source image brightness = 0.714 (all runs use seed 932 unless stated)')
print('%-22s %-8s %-7s %-6s %-9s %-10s %s' % ('variant', 'framemean', 'min', 'blank', 'motion', 'firstLast', 'verdict'))
for label, kind, path in CASES:
    f = load(path, kind)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    motion = float(np.abs(np.diff(f, axis=0)).mean()) if f.shape[0] > 1 else 0.0
    span = float(np.abs(f[0] - f[-1]).mean()) if f.shape[0] > 1 else 0.0
    if kind == 'image':
        verdict = 'reference'
    elif blank:
        verdict = 'BLACKOUT'
    elif span < 0.02:
        verdict = 'FROZEN'
    elif mean.mean() < 0.40:
        verdict = 'too dark'
    else:
        verdict = 'candidate'
    print('%-22s %-8.3f %-7.4f %-6d %-9.5f %-10.5f %s'
          % (label, mean.mean(), mean.min(), blank, motion, span, verdict))
