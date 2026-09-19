#!/usr/bin/env python3
"""同一档控制强度（alpha=0.25）在两个固定种子下的表现，以及与原灰填充的对照。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
CASES = [
    ('grey s42  (baseline)', E + '/window-20260914T065550Z/run-01/output.mp4'),
    ('grey s932 (blackout)', E + '/window-20260914T103811Z/run-01/output.mp4'),
    ('a=.25 s42  (candidate)', E + '/window-20260914T114940Z/run-01/output.mp4'),
    ('a=.25 s932 (candidate)', E + '/window-20260914T112215Z/run-01/output.mp4'),
]


def frames(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


print('source image brightness = 0.714')
print('%-24s %-8s %-7s %-7s %-9s %s' % ('variant', 'framemean', 'blank', 'dark', 'motion', 'firstLast'))
for label, path in CASES:
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    dark = int(np.count_nonzero(mean < 0.02))
    motion = float(np.abs(np.diff(f, axis=0)).mean())
    span = float(np.abs(f[0] - f[-1]).mean())
    print('%-24s %-8.3f %-7d %-7d %-9.5f %.5f' % (label, mean.mean(), blank, dark, motion, span))
