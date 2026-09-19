#!/usr/bin/env python3
"""细查灰度重绘在不同种子/素材下的亮度：均值 0.011 意味着画面几乎全黑。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
CASES = [
    ('V01 合成素材 s42', E + '/window-20260914T065550Z/run-03/output.mp4'),
    ('V01 合成素材 s932', E + '/window-20260914T124159Z/run-02/output.mp4'),
    ('V02 纹理源 s42(灰度)', E + '/window-20260914T093408Z/run-01/output.mp4'),
    ('V02 纹理源 s42(边缘)', E + '/window-20260914T093408Z/run-02/output.mp4'),
]


def frames(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


for label, path in CASES:
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    dark = int(np.count_nonzero(mean < 0.02))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    print('%-24s framemean %.4f  min %.5f  max %.4f  dark %2d/49  blank %d  peakMax %.3f'
          % (label, mean.mean(), mean.min(), mean.max(), dark, blank, peak.max()))
