#!/usr/bin/env python3
"""刻画 V01 合成素材产出的黑场：每帧平均亮度与峰值像素分布。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
for label, path in (('V01 合成 s42', E + '/window-20260914T065550Z/run-03/output.mp4'),
                    ('V01 合成 s932', E + '/window-20260914T124159Z/run-02/output.mp4')):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    f = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 480, 832, 3).astype(np.float32) / 255.0
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    # 亮像素占比：真正有内容的画面应远高于 1%
    bright_ratio = np.array([(frame.max(axis=2) > 0.5).mean() for frame in f])
    print(label)
    print('  per-frame mean : %s' % ' '.join('%.3f' % x for x in mean[:8]))
    print('  per-frame peak : %s' % ' '.join('%.3f' % x for x in peak[:8]))
    print('  bright-pixel fraction (per frame, first 8): %s' % ' '.join('%.4f' % x for x in bright_ratio[:8]))
    print('  mean bright-pixel fraction over clip: %.4f  | frames with <1%% bright pixels: %d/49'
          % (float(bright_ratio.mean()), int(np.count_nonzero(bright_ratio < 0.01))))
    print('  source asset brightness was 0.230 (synthetic dark scene)')
