#!/usr/bin/env python3
"""首帧修复候选（alpha=0.25）在"3 个主体 × 2 个固定种子"上的完整网格。

acceptance.md 要求每模式至少 3 个主体 × 2 个种子。这里逐格核对：
是否出现空白/全黑帧、亮度是否接近源图、是否有可见变化、跨种子是否一致。
"""
import subprocess
import sys

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
IN = '/opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/input'
GRID = {
    ('a (product)', 42): E + '/window-20260914T114940Z/run-01/output.mp4',
    ('a (product)', 932): E + '/window-20260914T112215Z/run-01/output.mp4',
    ('b (portrait)', 42): sys.argv[1] + '/run-01/output.mp4',
    ('c (landscape)', 42): sys.argv[1] + '/run-02/output.mp4',
    ('b (portrait)', 932): sys.argv[1] + '/run-03/output.mp4',
    ('c (landscape)', 932): sys.argv[2] + '/run-01/output.mp4',
}
SOURCES = {'a (product)': 'p0-subject-a.png', 'b (portrait)': 'p0-subject-b.png',
           'c (landscape)': 'p0-scene.png'}


def load(path, w=832, h=480):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo',
                          '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w, 3).astype(np.float32) / 255.0


print('%-16s %-6s %-9s %-7s %-7s %-9s %s' % ('subject', 'seed', 'framemean', 'blank', 'dark', 'motion', 'firstLast'))
rows = {}
for (subject, seed), path in GRID.items():
    f = load(path)
    mean = f.mean(axis=(1, 2, 3))
    peak = f.max(axis=(1, 2, 3))
    blank = int(np.count_nonzero((mean < 0.02) & (peak < 0.05)))
    dark = int(np.count_nonzero(mean < 0.02))
    motion = float(np.abs(np.diff(f, axis=0)).mean())
    span = float(np.abs(f[0] - f[-1]).mean())
    rows[(subject, seed)] = (mean.mean(), blank, dark, motion, span)
    print('%-16s %-6d %-9.3f %-7d %-7d %-9.5f %.5f' % (subject, seed, mean.mean(), blank, dark, motion, span))

print()
totals = [v[1] for v in rows.values()]
print('cells:', len(rows), '| cells with blank frames:', sum(1 for b in totals if b))
print('brightness range: %.3f - %.3f' % (min(v[0] for v in rows.values()), max(v[0] for v in rows.values())))
print('motion range: %.5f - %.5f' % (min(v[3] for v in rows.values()), max(v[3] for v in rows.values())))
print('firstLast range: %.5f - %.5f' % (min(v[4] for v in rows.values()), max(v[4] for v in rows.values())))
for subject in SOURCES:
    pair = [rows[(subject, s)][3] for s in (42, 932)]
    spread = abs(pair[0] - pair[1]) / max(pair) * 100
    print('cross-seed motion spread %-16s %.1f%%' % (subject, spread))
