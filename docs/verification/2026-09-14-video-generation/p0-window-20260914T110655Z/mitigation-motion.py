#!/usr/bin/env python3
"""检查变体是否只是把画面冻住：对比运动量、细节与首末帧差异。"""
import subprocess

import numpy as np

E = '/opt/yuyutian/mytools/runtime/video-generation/evidence'
IN = '/opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/input'
CASES = [('source image (still)', IN + '/p0-subject-a.png', 'image'),
         ('grey seed42', E + '/window-20260914T065550Z/run-01/output.mp4', 'video'),
         ('grey seed932 (blackout)', E + '/window-20260914T103811Z/run-01/output.mp4', 'video'),
         ('repeat seed932 (variant)', E + '/window-20260914T110655Z/run-01/output.mp4', 'video')]


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


for label, path, kind in CASES:
    f = load(path, kind)
    motion = float(np.abs(np.diff(f, axis=0)).mean()) if f.shape[0] > 1 else 0.0
    detail = float(np.abs(np.diff(f, axis=1)).mean())
    span = float(np.abs(f[0] - f[-1]).mean()) if f.shape[0] > 1 else 0.0
    print('%-26s frames %3d motion %.5f detail %.5f firstLastDiff %.5f'
          % (label, f.shape[0], motion, detail, span))
