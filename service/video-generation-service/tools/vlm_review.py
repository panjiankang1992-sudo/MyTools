#!/usr/bin/env python3
"""用本机视觉语言模型对生成视频做自动化观感复核，产出 acceptance.md 的五维评分。

用途说明（必须如实标注）：这不是人工评分。它用本地 VLM 看图后按 acceptance.md 的五个维度打分，
能发现"主体没了、画面糊、动作没发生、蒙版失效、明显伪影"这类问题，但**不能替代人眼对画质与
美感的判断**。因此结果一律标为"机器复核"，人工评分仍应另行完成。

评分维度与打分标尺
    指令遵循：画面是否体现了这一条 prompt 描述的动作/场景
    主体保持：与给它的参考图相比，主体是否还是同一个主体
    时间连贯：三帧之间是否连贯、没有跳变或闪烁
    编辑局部性：只在要求修改的区域发生变化（不适用时返回 null）
    伪影：全黑、破损、严重模糊、颜色崩坏等
"""

import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import urllib.request

# 评分维度固定，避免模型自创字段。
DIMENSIONS = ('instructionFollowing', 'subjectPreservation', 'temporalCoherence', 'editLocality', 'artifacts')
SEVERE_MARKERS = ('blank', 'all black', '全黑', 'broken', 'severely', 'unrecognizable', 'missing subject')


def scaled_size(width, height, max_side):
    """按最长边限制算出缩放后的尺寸；不超过限制时保持原尺寸。"""
    longest = max(width, height)
    if max_side <= 0 or longest <= max_side:
        return width, height
    ratio = max_side / float(longest)
    return max(1, int(round(width * ratio))), max(1, int(round(height * ratio)))


def encode(path, workdir, max_side=0):
    """把图片转成 base64；超过最大边长时先缩放。

    三张原图锚点加起来会超过本地服务的请求体上限（实测 400），缩放到 512 像素后
    既能通过，又足以判断主体是否存在、是否被替换这类结构问题。
    """
    source = Path(path)
    if max_side <= 0:
        return base64.b64encode(source.read_bytes()).decode('ascii')
    probe = json.loads(subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height',
         '-of', 'json', str(source)], check=True, capture_output=True, text=True).stdout)['streams'][0]
    width, height = scaled_size(int(probe['width']), int(probe['height']), max_side)
    folder = Path(workdir) / 'scaled'
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / (source.stem + f'-{width}x{height}.png')
    if not target.exists():
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(source), '-vf',
                        f'scale={width}:{height}', str(target)], check=True)
    return base64.b64encode(target.read_bytes()).decode('ascii')


def build_instruction(prompt, anchors, frames, mode):
    """构造复核指令，明确告诉模型每张图的角色。"""
    lines = [
        '你是视频生成结果的质检员。请只依据图片内容作答，不要猜测。',
        f'生成模式：{mode}。',
        f'生成时使用的提示词：{prompt}',
        '',
    ]
    if anchors:
        lines.append(f'前 {len(anchors)} 张图是"参考/输入素材"（第 1..{len(anchors)} 张）：')
        lines.append('它们是要求保持或作为起点的内容。')
    start = len(anchors) + 1
    lines.append(f'第 {start}..{start + len(frames) - 1} 张图是"生成视频的首帧、中间帧、末帧"（按时间顺序）。')
    lines.append('')
    lines.append('请比较参考素材与生成帧，输出严格的 JSON，字段如下：')
    lines.append('{"instructionFollowing": 1-5, "subjectPreservation": 1-5, '
                 '"temporalCoherence": 1-5, "editLocality": 1-5 或 null, '
                 '"artifacts": 1-5, "defects": "简短描述看到的问题，没有则写 none", '
                 '"summary": "一句话说明这段视频实际发生了什么"}')
    lines.append('打分标尺：5=很好，4=良好，3=可见问题但可接受，2=明显缺陷，1=严重失败。')
    lines.append('特别检查：主体是否消失或被替换；是否出现全黑/空白帧；动作是否根本没有发生；'
                 '只应修改的区域之外是否也变了。')
    lines.append('只输出 JSON，不要解释过程。')
    # qwen3 是思考模型：不加这个标记时它会一直思考到长度上限，content 为空。
    lines.append('/no_think')
    return '\n'.join(lines)


def extract_frames(video, folder, count=3):
    """抽取首帧、中间帧、末帧；已存在则复用。"""
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    existing = sorted(folder.glob('keyframe-*.png'))
    if len(existing) >= count:
        return existing[:count]
    probe = json.loads(subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=nb_frames',
         '-of', 'json', str(video)], check=True, capture_output=True, text=True).stdout)['streams'][0]
    total = int(probe['nb_frames'])
    candidates = [0, (total - 1) // 2, total - 1]
    wanted = candidates[:count] if count < 3 else candidates
    paths = []
    for index in wanted:
        path = folder / f'vlm-frame-{index:03d}.png'
        if not path.exists():
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video), '-vf',
                            f'select=eq(n\\,{index})', '-frames:v', '1', str(path)], check=True)
        paths.append(path)
    return paths


def parse_scores(text):
    """从模型回复里取出 JSON 并校验字段，解析失败即报错而不是猜。"""
    match = re.search(r'\{.*\}', text, re.DOTALL)
    if not match:
        raise ValueError('VLM_REVIEW_NOT_JSON')
    document = json.loads(match.group(0))
    scores = {}
    for name in DIMENSIONS:
        value = document.get(name)
        if value is None and name == 'editLocality':
            scores[name] = None
            continue
        if not isinstance(value, (int, float)) or not 1 <= float(value) <= 5:
            raise ValueError('VLM_REVIEW_SCORE_INVALID:' + name)
        scores[name] = int(round(float(value)))
    for name in ('defects', 'summary'):
        scores[name] = ' '.join(str(document.get(name) or '').split())[:400]
    return scores


def ask(model, endpoint, instruction, images, timeout=600):
    """调用本地 Ollama 的对话接口，要求返回 JSON。"""
    # think=False 关闭思考链；temperature=0 让同一份证据得到可复现的评分。
    payload = {'model': model, 'stream': False, 'format': 'json', 'think': False,
               'options': {'temperature': 0, 'num_predict': 900},
               'messages': [{'role': 'user', 'content': instruction, 'images': images}]}
    request = urllib.request.Request(endpoint.rstrip('/') + '/api/chat',
                                     data=json.dumps(payload).encode(),
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.load(response)
    message = body.get('message') or {}
    # qwen3 系列是思考模型：JSON 有时落在 content，有时只在 thinking 里，两处都要看。
    for field in ('content', 'thinking'):
        text = message.get(field)
        if text and re.search(r'\{.*\}', text, re.DOTALL):
            return text
    raise ValueError('VLM_REVIEW_EMPTY_RESPONSE')


def severe(scores):
    """按 acceptance.md 的严重失败判据做一个保守标记。"""
    if scores.get('artifacts', 5) <= 2:
        return True
    defects = (scores.get('defects') or '').lower()
    return any(marker.lower() in defects for marker in SEVERE_MARKERS)


def load_plan(path):
    """读取批量复核计划并逐项校验，缺字段直接报错而不是跳过。"""
    plan = json.loads(Path(path).read_text())
    if not isinstance(plan, list) or not plan:
        raise ValueError('VLM_PLAN_EMPTY')
    for entry in plan:
        for key in ('runId', 'video', 'prompt', 'mode', 'workdir'):
            if not entry.get(key):
                raise ValueError('VLM_PLAN_FIELD_MISSING:' + str(key))
        entry.setdefault('anchors', [])
    return plan


# 本地服务的图片数上限实测为 5：超过就返回 400。参考图多时相应减少抽帧数。
MAX_IMAGES = 5


def frame_budget(anchor_count, limit=MAX_IMAGES):
    """在总图片数受限时，算出能给生成视频留几帧。

    可选帧位只有首/中/末三个，所以上限是 3；参考图再多也至少留 1 帧。
    """
    return max(1, min(3, limit - anchor_count))


def review(model, endpoint, video, prompt, mode, anchors, workdir, max_side=512):
    """对单个视频执行一次机器复核，返回记录。"""
    frames = extract_frames(video, workdir, count=frame_budget(len(anchors)))
    instruction = build_instruction(prompt, anchors, frames, mode)
    images = [encode(path, workdir, max_side) for path in list(anchors) + frames]
    reply = ask(model, endpoint, instruction, images)
    scores = parse_scores(reply)
    return {'runId': Path(workdir).name, 'video': str(video), 'mode': mode,
            'reviewer': 'automated-vlm:' + model, 'notHumanReview': True,
            'anchors': [str(path) for path in anchors],
            'frames': [str(path) for path in frames],
            'scores': {key: scores[key] for key in DIMENSIONS},
            'defects': scores['defects'], 'summary': scores['summary'],
            'severeFlag': severe(scores)}


def main():
    """对单个视频产出一次机器复核记录；给了 --plan 则批量执行。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', help='批量复核计划 JSON；给了它就不用 --video')
    parser.add_argument('--video')
    parser.add_argument('--prompt')
    parser.add_argument('--mode')
    parser.add_argument('--anchor', action='append', default=[])
    parser.add_argument('--model', default='huihui_ai/qwen3-vl-abliterated:8b')
    parser.add_argument('--endpoint', default='http://127.0.0.1:11434')
    parser.add_argument('--workdir', help='单条模式的临时目录；批量模式用计划里的 workdir')
    parser.add_argument('--max-side', type=int, default=512,
                        help='送入模型前的最长边像素；0 表示不缩放')
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.plan:
        records = []
        for entry in load_plan(args.plan):
            try:
                record = review(args.model, args.endpoint, entry['video'], entry['prompt'],
                                entry['mode'], entry['anchors'], entry['workdir'], args.max_side)
                record['runId'] = entry['runId']
            except Exception as error:  # 单项失败不掩盖其他结果。
                record = {'runId': entry['runId'], 'error': type(error).__name__ + ':' + str(error)[:200],
                          'notHumanReview': True}
            records.append(record)
            print(json.dumps({'runId': record['runId'],
                              'scores': record.get('scores'), 'defects': record.get('defects'),
                              'error': record.get('error')}, ensure_ascii=False), flush=True)
        if args.output:
            Path(args.output).write_text(json.dumps(records, indent=2, ensure_ascii=False) + '\n')
        return
    if not (args.video and args.prompt and args.mode):
        raise SystemExit('need --video/--prompt/--mode, or --plan')
    record = review(args.model, args.endpoint, args.video, args.prompt, args.mode,
                    args.anchor, args.workdir, args.max_side)
    if args.output:
        Path(args.output).write_text(json.dumps(record, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(record, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
