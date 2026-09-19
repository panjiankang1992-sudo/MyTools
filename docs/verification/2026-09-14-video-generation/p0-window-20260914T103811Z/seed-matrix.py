#!/usr/bin/env python3
"""四个种子下首帧模式的产出矩阵：是否出现空白/黑场，以及亮度保持情况。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
CASES = [('42', E + '/window-20260914T065550Z/run-01/output.mp4'),
         ('932', E + '/window-20260914T103811Z/run-01/output.mp4'),
         ('933', E + '/window-20260914T103811Z/run-02/output.mp4'),
         ('934', E + '/window-20260914T103811Z/run-03/output.mp4')]


def frames(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


print('source image brightness = 0.714')
print('%-6s %-9s %-9s %-11s %-11s %s' % ('seed', 'framemean', 'min', 'blank', 'dark', 'verdict'))
for seed, path in CASES:
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    dark = int(np.count_nonzero(mean < 0.02))
    verdict = 'BLACKOUT' if blank else ('dark-mostly' if dark else 'ok')
    print('%-6s %-9.3f %-9.4f %-11d %-11d %s' % (seed, mean.mean(), mean.min(), blank, dark, verdict))
