#!/usr/bin/env python3
"""第二个种子的覆盖复测：81 帧文生、三图参考、以及分镜镜头 2 的暗场是否复现。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
W = E + '/window-20260914T131431Z'
CASES = [
    ('T01-81 文生81帧 s42', E + '/window-20260914T082046Z/run-03/output.mp4'),
    ('T01-81 文生81帧 s932', W + '/run-01/output.mp4'),
    ('R03 三图参考 s42', E + '/window-20260914T082046Z/run-02/output.mp4'),
    ('R03 三图参考 s932', W + '/run-02/output.mp4'),
    ('S01 镜头2 s42', E + '/window-20260914T090441Z/run-02/output.mp4'),
    ('S01 镜头2 s932', W + '/run-03/output.mp4'),
]


def frames(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


for label, path in CASES:
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    dark = int(np.count_nonzero(mean < 0.02))
    motion = float(np.abs(np.diff(f, axis=0)).mean())
    print('%-22s frames %2d  framemean %.4f  min %.4f  blank %d  dark %2d  motion %.5f'
          % (label, f.shape[0], mean.mean(), mean.min(), blank, dark, motion))

print()
print('=== 分镜镜头2 的逐帧亮度（看暗场是否复现） ===')
for label, path in (('s42 ', E + '/window-20260914T090441Z/run-02/output.mp4'), ('s932', W + '/run-03/output.mp4')):
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    print('%s: %s' % (label, ' '.join('%.2f' % x for x in mean[:20])))
