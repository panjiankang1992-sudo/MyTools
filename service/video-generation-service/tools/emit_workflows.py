#!/usr/bin/env python3
"""生成 P0 固定工作流模板。

每个模板固定模型、采样器与节点类型，只允许注入 prompt、输入文件、seed、尺寸与帧数；
客户端不能提交任意节点图。模板哈希随证据归档，模板本身不因运行结果而修改。
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from workflow import build

# 首帧家族（官方灰填充、参考图填充、若干混合档）共用同一段 prompt，
# 这样变体之间只差控制内容，可以直接对照控制强度。
FIRSTFRAME_PROMPT = ('The subject in the frame begins to move gently. The camera slowly pushes in. '
                     'The opening composition and colours stay unchanged.')

# 输入文件名固定为 Comfy 输入目录下的扁平名字，避免依赖子目录解析。
TEMPLATES = [
    {
        'name': 't2v',
        'mode': 'TEXT_TO_VIDEO',
        'prompt': 'A red ceramic teapot on a wooden table gently releases steam. '
                  'The camera slowly moves closer. Warm soft daylight.',
    },
    {
        'name': 'firstframe',
        'mode': 'FIRST_FRAME',
        'prompt': FIRSTFRAME_PROMPT,
        'control': 'p0-firstframe-control.mp4',
        'mask': 'p0-firstframe-mask.mp4',
    },
    {
        'name': 'subject-references',
        'mode': 'SUBJECT_REFERENCES',
        'prompt': 'Both reference subjects appear together in one scene on a wooden table. '
                  'The camera slowly moves closer. Keep each subject distinct and unchanged.',
        'reference': ['p0-subject-a.png', 'p0-subject-b.png'],
    },
    {
        # 与 subject-references 只有第二张参考图不同：用于验证"第二张图确实影响结果"的对照，
        # 因此 prompt 字符串必须与上一个模板逐字相同。
        'name': 'subject-references-alt',
        'mode': 'SUBJECT_REFERENCES',
        'prompt': 'Both reference subjects appear together in one scene on a wooden table. '
                  'The camera slowly moves closer. Keep each subject distinct and unchanged.',
        'reference': ['p0-subject-a.png', 'p0-scene.png'],
    },
    {
        'name': 'firstlast',
        'mode': 'FIRST_LAST_FRAMES',
        'prompt': 'The scene transitions smoothly from the first image to the last image. '
                  'The camera moves slowly and the lighting stays consistent.',
        'control': 'p0-firstlast-control.mp4',
        'mask': 'p0-firstlast-mask.mp4',
    },
    {
        # 与 firstframe 只差"生成区间的控制内容"：官方默认填 127.5 灰，这里改填参考图本身，
        # 掩码完全一致。用于验证弱内容先验是否会导致采样塌陷出全黑帧，prompt 必须逐字相同。
        'name': 'firstframe-repeat',
        'mode': 'FIRST_FRAME',
        'prompt': FIRSTFRAME_PROMPT,
        'control': 'p0-firstframe-repeat-control.mp4',
        'mask': 'p0-firstframe-repeat-mask.mp4',
    },
    {
        # 控制强度扫描：blend 在灰(0.502)与参考图(0.720)之间插值，用于找"既不全黑也不静止"的中间档。
        'name': 'firstframe-blend025',
        'mode': 'FIRST_FRAME',
        'prompt': FIRSTFRAME_PROMPT,
        'control': 'p0-firstframe-blend025-control.mp4',
        'mask': 'p0-firstframe-blend025-mask.mp4',
    },
    {
        'name': 'firstframe-blend050',
        'mode': 'FIRST_FRAME',
        'prompt': FIRSTFRAME_PROMPT,
        'control': 'p0-firstframe-blend050-control.mp4',
        'mask': 'p0-firstframe-blend050-mask.mp4',
    },
    {
        'name': 'firstframe-blend075',
        'mode': 'FIRST_FRAME',
        'prompt': FIRSTFRAME_PROMPT,
        'control': 'p0-firstframe-blend075-control.mp4',
        'mask': 'p0-firstframe-blend075-mask.mp4',
    },
    {
        'name': 'subject-references-three',
        'mode': 'SUBJECT_REFERENCES',
        'prompt': 'All three reference subjects appear together in one scene. '
                  'The camera slowly moves closer. Keep each subject distinct and unchanged.',
        'reference': ['p0-subject-a.png', 'p0-subject-b.png', 'p0-scene.png'],
    },
    {
        'name': 'masked-edit',
        'mode': 'MASKED_EDIT',
        'prompt': 'In the right half of the frame the orange square becomes a glossy blue sphere. '
                  'Everything in the left half stays exactly as it is, including the background.',
        'control': 'p0-masked-control.mp4',
        'mask': 'p0-masked-mask.mp4',
    },
    {
        'name': 'restyle-gray',
        'mode': 'STRUCTURE_RESTYLE',
        'prompt': 'A watercolor painting on textured paper. The orange square keeps moving to the right '
                  'at the same speed and direction, and the blue square stays in place. '
                  'Visible brush strokes, soft edges, muted palette.',
        'control': 'p0-restyle-control-gray.mp4',
    },
    {
        # 与 restyle-gray 只差控制信号：用于 V02 要求的"同一源片对比灰度/边缘"，
        # 因此 prompt 字符串必须与 gray 分支逐字相同。
        'name': 'restyle-edge',
        'mode': 'STRUCTURE_RESTYLE',
        'prompt': 'A watercolor painting on textured paper. The orange square keeps moving to the right '
                  'at the same speed and direction, and the blue square stays in place. '
                  'Visible brush strokes, soft edges, muted palette.',
        'control': 'p0-restyle-control-edge.mp4',
    },
    {
        # 纹理源片（横移照片）上的重绘：合成几何体的边缘过于稀疏，不适合评估结构控制。
        # 与 restyle-scene-edge 只差控制信号，prompt 必须逐字相同。
        'name': 'restyle-scene-gray',
        'mode': 'STRUCTURE_RESTYLE',
        'prompt': 'The camera pan over the landscape is repainted as a watercolor on textured paper. '
                  'The pan direction, speed and framing stay the same. '
                  'Visible brush strokes, soft edges, muted palette.',
        'control': 'p0-scene-control-gray.mp4',
    },
    {
        'name': 'restyle-scene-edge',
        'mode': 'STRUCTURE_RESTYLE',
        'prompt': 'The camera pan over the landscape is repainted as a watercolor on textured paper. '
                  'The pan direction, speed and framing stay the same. '
                  'Visible brush strokes, soft edges, muted palette.',
        'control': 'p0-scene-control-edge.mp4',
    },
]

# 分镜：每张图对应一个镜头，逐镜串行生成后再拼接。这不是一次多图条件推理，
# 每个镜头都是独立的 FIRST_FRAME 工作流，只共用同一套采样参数与种子。
STORYBOARD = [
    {
        'name': 'storyboard-shot1',
        'mode': 'FIRST_FRAME',
        'prompt': 'Shot 1. The product in the opening frame turns slowly on the table. '
                  'The camera holds still and the lighting is unchanged.',
        'control': 'p0-storyboard-shot1-control.mp4',
        'mask': 'p0-storyboard-shot1-mask.mp4',
    },
    {
        'name': 'storyboard-shot2',
        'mode': 'FIRST_FRAME',
        'prompt': 'Shot 2. The person in the opening frame slowly turns their head toward the camera. '
                  'The camera pushes in slightly and the background stays the same.',
        'control': 'p0-storyboard-shot2-control.mp4',
        'mask': 'p0-storyboard-shot2-mask.mp4',
    },
    {
        'name': 'storyboard-shot3',
        'mode': 'FIRST_FRAME',
        'prompt': 'Shot 3. The landscape in the opening frame gains a gentle breeze; '
                  'the camera pans slowly to the right and the lighting stays warm.',
        'control': 'p0-storyboard-shot3-control.mp4',
        'mask': 'p0-storyboard-shot3-mask.mp4',
    },
]

# 每个分镜镜头各自的起始图，与 STORYBOARD 顺序一一对应。
STORYBOARD_IMAGES = ['p0-subject-a.png', 'p0-subject-b.png', 'p0-scene.png']


def retag(template, tag):
    """把模板引用的输入文件名加上主体标签，便于用同一套参数跑不同主体。

    只改控制/掩码文件名，prompt 与采样参数完全不动，因此主体是唯一变量。
    """
    if not tag:
        return template
    tagged = dict(template)
    for key in ('control', 'mask'):
        if key in tagged:
            stem, _, extension = tagged[key].rpartition('.')
            tagged[key] = f'{stem}-{tag}.{extension}'
    return tagged


def select(suffix, only, storyboard=False, tag=''):
    """按名称与后缀筛选模板，便于生成 81 帧或分镜等变体而不改动基准模板。"""
    if storyboard:
        return [(retag(template, tag), template['name'] + suffix) for template in STORYBOARD]
    if not only:
        return [(retag(template, tag), template['name'] + suffix) for template in TEMPLATES]
    by_name = {template['name']: template for template in TEMPLATES}
    missing = [name for name in only if name not in by_name]
    if missing:
        raise ValueError('VIDEO_TEMPLATE_UNKNOWN:' + ','.join(missing))
    return [(retag(by_name[name], tag), name + suffix) for name in only]


def main():
    """写出模板并打印哈希，便于随证据记录。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--frames', type=int, default=49)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--only', action='append', default=[])
    parser.add_argument('--suffix', default='')
    parser.add_argument('--input-tag', default='',
                        help='给控制/掩码文件名加主体标签，用于同一模式跑不同主体')
    parser.add_argument('--storyboard', action='store_true',
                        help='输出分镜镜头工作流（逐镜独立生成，不共用一次多图条件推理）')
    args = parser.parse_args()
    folder = Path(args.output_dir)
    folder.mkdir(parents=True, exist_ok=True)
    emitted = []
    for template, filename in select(args.suffix, args.only, args.storyboard, args.input_tag):
        parameters = {key: value for key, value in template.items() if key not in ('name', 'mode', 'prompt')}
        graph = build(template['prompt'], mode=template['mode'], seed=args.seed, frames=args.frames, **parameters)
        path = folder / (filename + '-workflow.json')
        payload = json.dumps(graph, indent=2) + '\n'
        path.write_text(payload)
        emitted.append({'name': filename, 'mode': template['mode'], 'path': str(path),
                        'sha256': hashlib.sha256(payload.encode()).hexdigest()})
        print(json.dumps(emitted[-1]), flush=True)
    (folder / ('templates' + args.suffix + '.json')).write_text(
        json.dumps({'seed': args.seed, 'frames': args.frames, 'templates': emitted}, indent=2) + '\n')


if __name__ == '__main__':
    main()
