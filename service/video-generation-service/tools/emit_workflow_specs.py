#!/usr/bin/env python3
"""把已验证的 VACE 工作流固化成"不可变规格 + 精确绑定"文件。

生产执行器不解析客户端节点图，只读取这里的规格：节点类型必须落在白名单内，
每个可变字段都通过 `bindings` 精确指向节点输入，禁止文本模板插值。

规格由 `workflow.build()` 自身生成（注入哨兵值后反查节点输入），因此规格里的
节点与绑定不可能和已验证的构图代码漂移；任何构图改动都会改变规格哈希。
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from workflow import build

# 哨兵值只在生成期使用；它们唯一标识"哪个节点输入是可变的"。
PROMPT = '__VIDEO_PROMPT__'
CONTROL = '__VIDEO_CONTROL__'
MASK = '__VIDEO_MASK__'
PREFIX = '__VIDEO_PREFIX__'
SEED = 987654321
REFERENCES = ('__VIDEO_REF0__', '__VIDEO_REF1__', '__VIDEO_REF2__')

# 每个模式需要客户端或服务端提供哪些可变输入；执行器据此拒绝缺失或多给的输入。
# 与 VideoCapability 暴露的模式一一对应：三图参考在 P0 判为不可用，因此不生成规格。
SPECS = [
    {'mode': 'TEXT_TO_VIDEO', 'references': 0, 'control': False, 'mask': False},
    {'mode': 'SUBJECT_REFERENCES', 'references': 2, 'control': False, 'mask': False},
    {'mode': 'FIRST_FRAME', 'references': 0, 'control': True, 'mask': True},
    {'mode': 'FIRST_LAST_FRAMES', 'references': 0, 'control': True, 'mask': True},
    {'mode': 'STRUCTURE_RESTYLE', 'references': 0, 'control': True, 'mask': False},
    {'mode': 'MASKED_EDIT', 'references': 0, 'control': True, 'mask': True},
]

# 固定采样参数；客户端不能覆盖。
NEGATIVE = 'blurry, static, distorted, deformed, text, watermark, flickering'


def bindings(graph):
    """反查哨兵值出现的位置，得到逻辑名到节点输入的精确绑定。"""
    found = {}
    for node, body in graph.items():
        for field, value in body['inputs'].items():
            if value == PROMPT:
                found.setdefault('prompt', []).append([node, field])
            elif value == CONTROL:
                found.setdefault('control', []).append([node, field])
            elif value == MASK:
                found.setdefault('mask', []).append([node, field])
            elif value == PREFIX:
                found.setdefault('prefix', []).append([node, field])
            elif value == SEED:
                found.setdefault('seed', []).append([node, field])
            elif isinstance(value, str) and value in REFERENCES:
                found.setdefault('reference' + str(REFERENCES.index(value)), []).append([node, field])
    return found


def spec_for(entry, frames):
    """为单个模式生成规格；references 数量由模式声明决定。"""
    references = [REFERENCES[index] for index in range(entry['references'])]
    graph = build(PROMPT, seed=SEED, frames=frames, mode=entry['mode'],
                  reference=references or None, control=CONTROL if entry['control'] else None,
                  mask=MASK if entry['mask'] else None, prefix=PREFIX)
    # 尺寸与帧数由构图代码固定（832×480、49 帧），属于规格常量而不是逐任务输入，
    # 因此不进入绑定表；任何越界值都会在构图阶段直接报错。
    positions = bindings(graph)
    # prefix 决定 Comfy 的输出子目录，必须逐任务注入，避免不同任务互相覆盖。
    required = ['prompt', 'seed', 'prefix']
    if entry['control']:
        required.append('control')
    if entry['mask']:
        required.append('mask')
    for index in range(entry['references']):
        required.append('reference' + str(index))
    missing = [name for name in required if name not in positions]
    if missing:
        raise ValueError('VIDEO_SPEC_BINDING_MISSING:' + ','.join(missing))
    unexpected = sorted(set(positions) - set(required))
    if unexpected:
        # 出现未声明的可变输入说明构图里有没被记录的可变字段，必须停下来人工确认。
        raise ValueError('VIDEO_SPEC_BINDING_UNEXPECTED:' + ','.join(unexpected))
    return {
        'revision': 'video-' + entry['mode'].lower().replace('_', '-') + '-v1',
        'mode': entry['mode'],
        'frames': frames,
        'width': 832,
        'height': 480,
        'fps': 16,
        'negative': NEGATIVE,
        'outputNode': '11',
        'allowedClassTypes': sorted({body['class_type'] for body in graph.values()}),
        'graph': graph,
        'bindings': positions,
        'requiredInputs': sorted(required),
    }


def main():
    """写出规格与索引，并打印每个文件的 SHA-256 供部署固定。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--frames', type=int, default=49)
    args = parser.parse_args()
    if args.frames != 49:
        # 目前只有 49 帧通过验收；81 帧需要单独的维护窗口结论。
        raise ValueError('VIDEO_FRAMES_NOT_VALIDATED')
    folder = Path(args.output_dir)
    folder.mkdir(parents=True, exist_ok=True)
    index = {}
    for entry in SPECS:
        payload = json.dumps(spec_for(entry, args.frames), indent=2, sort_keys=True) + '\n'
        name = entry['mode'].lower() + '-v1.json'
        (folder / name).write_text(payload)
        index[entry['mode']] = {'file': name, 'sha256': hashlib.sha256(payload.encode()).hexdigest()}
    index_payload = json.dumps({'revision': 'video-vace-1.3b-v1', 'frames': args.frames,
                                'specs': index}, indent=2, sort_keys=True) + '\n'
    (folder / 'index.json').write_text(index_payload)
    print(json.dumps({'index': str(folder / 'index.json'),
                      'sha256': hashlib.sha256(index_payload.encode()).hexdigest(),
                      'specs': len(index)}, indent=2))


if __name__ == '__main__':
    main()
