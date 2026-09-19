#!/usr/bin/env python3
"""对比 seed 932 下"灰填充"与"参考图填充"的产出，判断全黑是否消失。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
CASES = [('grey fill (official)', E + '/window-20260914T103811Z/run-01/output.mp4'),
         ('repeat fill (variant)', E + '/window-20260914T110655Z/run-01/output.mp4'),
         ('grey fill @seed42 (good)', E + '/window-20260914T065550Z/run-01/output.mp4')]


def frames(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


print('source image brightness = 0.714')
for label, path in CASES:
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    dark = int(np.count_nonzero(mean < 0.02))
    print('%-24s framemean %.3f min %.4f blank %d dark %d' % (label, mean.mean(), mean.min(), blank, dark))
    print('   first 8 frames:', ' '.join('%.2f' % x for x in mean[:8]))
