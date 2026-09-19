#!/usr/bin/env python3
"""跨模式 × 跨种子的缺陷矩阵：用已知会触发塌陷的 seed 932 去探其他模式。

目的不是再跑一遍同一个模式，而是检验"生成区间缺少内容先验就会塌陷"这个假设是否泛化，
并顺带确认哪些模式在两个固定种子下都稳定。
"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
PROBE = E + '/window-20260914T124159Z'
CASES = [
    ('T2V 文生', E + '/trial-20260914T063810Z/output.mp4', E + '/window-20260914T095836Z/run-01/output.mp4'),
    ('I01 单图首帧', E + '/window-20260914T065550Z/run-01/output.mp4', E + '/window-20260914T103811Z/run-01/output.mp4'),
    ('R01 两图参考', E + '/window-20260914T074325Z/run-01/output.mp4', E + '/window-20260914T095836Z/run-03/output.mp4'),
    ('F01 首尾帧', E + '/window-20260914T074325Z/run-03/output.mp4', PROBE + '/run-01/output.mp4'),
    ('V01 灰度重绘', E + '/window-20260914T065550Z/run-03/output.mp4', PROBE + '/run-02/output.mp4'),
    ('M01 局部修改', E + '/window-20260914T082046Z/run-01/output.mp4', PROBE + '/run-03/output.mp4'),
]


def frames(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


def measure(path):
    f = frames(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    return {'bright': float(mean.mean()),
            'blank': int(np.count_nonzero((mean < 0.02) & (peak < 0.05))),
            'dark': int(np.count_nonzero(mean < 0.02)),
            'motion': float(np.abs(np.diff(f, axis=0)).mean())}


print('%-16s %-28s %-28s' % ('模式', 'seed 42', 'seed 932'))
print('%-16s %-28s %-28s' % ('', '亮度/全黑/运动', '亮度/全黑/运动'))
flagged = []
for label, p42, p932 in CASES:
    a, b = measure(p42), measure(p932)
    span = abs(a['motion'] - b['motion']) / max(a['motion'], b['motion']) * 100
    print('%-16s %.3f / %d / %.5f%s %.3f / %d / %.5f%s  运动差异 %4.1f%%'
          % (label, a['bright'], a['blank'], a['motion'], ' ' * 6,
             b['bright'], b['blank'], b['motion'], ' ' * 6, span))
    if b['blank'] or a['blank']:
        flagged.append(label)
print()
print('在 seed 932 上出现全黑帧的模式:', flagged or '无')
