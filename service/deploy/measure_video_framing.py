"""用首末帧二维缩放和平移拟合辅助复核运镜，不自动判定三维相机运动。"""
import argparse
import json
import subprocess
import numpy as np


def fit(first, last, scales):
    """在中心区域拟合亮度归一化误差，保留缩放和平移的竞争结果。"""
    height, width = first.shape
    yy, xx = np.mgrid[height//5:height*4//5:2, width//5:width*4//5:2]
    target = last[yy, xx].astype(float)
    target -= target.mean()
    best = None
    for scale in scales:
        for dx in range(-24, 25, 2):
            for dy in range(-24, 25, 2):
                sx = np.rint((xx-width/2-dx)/scale+width/2).astype(int)
                sy = np.rint((yy-height/2-dy)/scale+height/2).astype(int)
                if sx.min()<0 or sy.min()<0 or sx.max()>=width or sy.max()>=height:
                    continue
                source = first[sy,sx].astype(float)
                source -= source.mean()
                error = float(np.mean((source-target)**2))
                if best is None or error < best['mse']:
                    best = {'scale':float(scale),'dxAt128':dx,'dyAt128':dy,'mse':error}
    return best


def measure(path):
    """解码两帧并比较平移模型和缩放模型，输出仅用于辅助人工或 AI 审查。"""
    raw = subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-vf',
        'select=eq(n\\,0)+eq(n\\,96),scale=128:128,format=gray','-fps_mode','passthrough',
        '-f','rawvideo','-'])
    frames = np.frombuffer(raw,dtype=np.uint8).reshape(-1,128,128)
    if len(frames) != 2:
        raise ValueError('expected_97_frame_clip')
    return {'translationOnly':fit(frames[0],frames[1],[1.0]),
            'scaleAndTranslation':fit(frames[0],frames[1],np.arange(.8,1.501,.025)),
            'note':'Two-dimensional fit only; not proof of physical camera trajectory.'}


if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('video');args=parser.parse_args()
    print(json.dumps(measure(args.video),indent=2))
