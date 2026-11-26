#!/usr/bin/env python3
"""首帧模式的验收窗口：多素材 × 多种子跑真实任务，量测补边是否仍是中性灰。

窗口内暂停 GPU 竞争任务；忙时拒绝开始，新生产任务入队时取消本测试并恢复。
不修改生产能力开关，异常与正常结束都恢复容量和图片服务。

与 verify 的区别是覆盖多张素材与多个种子：补边缺陷曾经"白底素材通过、暖色素材失败"，
所以验收必须跨素材，不能只靠一张。

用法（目标机 root）：
    VIDEO_DEPLOY_NAME=<release> python3 accept_video_first_frame.py --subjects a.png:冷白底 b.png:暖色 …
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from video_acceptance_window import active_competitors, window, write_state

DEPLOY = Path(__file__).resolve().parent / 'deploy_video_generation.py'
SEEDS = (42, 932)
# 色偏判据用"色平衡"而不是单通道绝对值：整段均匀变暗（三通道同向）不是色偏，
# 而白底素材漂蓝时 R−B 会变化上百。实测 α=0.25：A 108.9、B 4.8、C 25.0；取 40 作"明显色偏"门槛。
CONTENT_CAST_LIMIT = 40.0
# 运动量下限/上限：低于下限说明"没动"，高于上限说明画面在乱跳。
MOTION_FLOOR = 1.5
MOTION_CEILING = 60.0
SAMPLED_FRAMES = (0, 24, 48)
# 固定提示词：验收比的是补边与构图，不引入提示词变量。
PROMPT = ('The subject in the frame begins to move gently. The camera slowly pushes in. '
          'The opening composition and colours stay unchanged.')


def load_deploy():
    """加载同目录的发布脚本，复用它的请求、量测与窗口逻辑。"""
    spec = importlib.util.spec_from_file_location('deploy_video_generation', DEPLOY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def frame_png(video, index, target):
    """抽出关键帧存成 PNG，作为可复核的证据。"""
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video), '-vf', f'select=eq(n\\,{index})',
                    '-frames:v', '1', str(target)], check=True, timeout=120)
    return target


def content_means(video, index):
    """量测内容区（去掉两侧补边与最外圈）的通道均值，用来发现"补边是灰的、内容却变色了"。"""
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-vf', f'select=eq(n\\,{index})',
                          '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    if len(raw) != 832 * 480 * 3:
        return None
    totals = [0, 0, 0]
    count = 0
    for row in range(40, 440):
        base = row * 832 * 3
        for column in range(200, 632):
            offset = base + column * 3
            for channel in range(3):
                totals[channel] += raw[offset + channel]
            count += 1
    return [round(total / count, 1) for total in totals]


def content_drift(video, frames):
    """内容区颜色分析：逐通道漂移 + 色平衡漂移 + 亮度漂移。

    判据只卡"色偏"（色平衡变化），不卡整体变暗/变亮：三通道同向变化属于亮度变化，可能是模型
    合理的运动结果，由人评在指令遵循里判断；色平衡变化才是"变色"这类缺陷。
    """
    values = [content_means(video, index) for index in frames]
    if not values or any(value is None for value in values):
        return None
    first, last = values[0], values[-1]
    balance = [round(v[0] - v[2], 1) for v in values]          # R−B：色温/色偏
    green = [round(v[1] - (v[0] + v[2]) / 2, 1) for v in values]  # 绿-品红轴
    luma = [round(sum(v) / 3, 1) for v in values]
    return {'frames': values,
            'delta': [round(b - a, 1) for a, b in zip(first, last)],
            'balance': balance, 'greenAxis': green, 'luma': luma,
            'deltaBalance': round(balance[-1] - balance[0], 1),
            'deltaGreenAxis': round(green[-1] - green[0], 1),
            'deltaLuma': round(luma[-1] - luma[0], 1),
            'maxAbsCast': round(max(abs(balance[-1] - balance[0]), abs(green[-1] - green[0])), 1)}


def edges_are_neutral(deploy, edges):
    """补边是否仍是中性灰：亮度在带内且三通道离散度不超限。"""
    limit = deploy.EDGE_CHANNEL_SPREAD_LIMIT / 255.0
    for item in edges:
        for side in ('left', 'right'):
            if item[side + 'Spread'] > limit:
                return False
            if any(abs(value - 0.502) > deploy.EDGE_BRIGHTNESS_BAND
                   for value in item[side + 'Channels']):
                return False
    return True


def motion(video):
    """首帧与中间帧的平均绝对差（0–255）：用来证明画面确实在动。"""
    def gray(index):
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-vf',
                              f'select=eq(n\\,{index}),format=gray', '-frames:v', '1',
                              '-f', 'rawvideo', '-'], check=True, capture_output=True).stdout
        return raw
    first, middle = gray(0), gray(24)
    if not first or len(first) != len(middle):
        return None
    return round(sum(abs(a - b) for a, b in zip(first, middle)) / len(first), 4)


def wait_window_job(deploy, job_id, definitions):
    """轮询期间让生产任务优先，异常由窗口恢复器取消本测试作业。"""
    deadline = time.monotonic() + 2400
    while time.monotonic() < deadline:
        if active_competitors(deploy, definitions):
            raise RuntimeError('production_gpu_task_arrived')
        code, result, _ = deploy.request(23341, '/internal/v1/videos/jobs/' + job_id)
        if code == 200 and result.get('status') in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'PARTIAL_SUCCESS'):
            return result
        time.sleep(10)
    raise RuntimeError('job_timeout')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--subjects', nargs='+', required=True,
                        help='素材路径:标签，例如 /tmp/a.png:冷白底')
    parser.add_argument('--output', required=True, help='证据目录')
    parser.add_argument('--count', type=int, help='连续串行任务数；循环使用素材与种子。')
    parsed = parser.parse_args()
    if parsed.count is not None and not 1 <= parsed.count <= 30:
        parser.error('count must be between 1 and 30')
    subjects = []
    for item in parsed.subjects:
        # 允许 path:标签:slug 三段；slug 必须是 ASCII——它要进幂等键与文件名，
        # 中文标签会让服务端按非法参数拒掉（实测 VIDEO_001）。
        parts = item.split(':')
        path = parts[0]
        label = parts[1] if len(parts) > 1 and parts[1] else Path(path).stem
        slug = parts[2] if len(parts) > 2 and parts[2] else f'subject{len(subjects) + 1}'
        if not slug.isascii():
            raise SystemExit('slug_must_be_ascii ' + slug)
        subjects.append({'path': Path(path), 'label': label, 'slug': slug})
        if not Path(path).is_file():
            raise SystemExit('subject_missing ' + path)
    deploy = load_deploy()
    output = Path(parsed.output)
    output.mkdir(parents=True, exist_ok=True)

    # 只复验已经开放的模式；客观测试不会改写人工验收或生产能力开关。
    if deploy.values(deploy.ENV).get('VIDEO_FIRST_FRAME_VALIDATED') != 'true':
        raise SystemExit('first_frame_not_enabled')
    cases = [(subject, seed) for subject in subjects for seed in SEEDS]
    if parsed.count is not None:
        cases = [cases[index % len(cases)] for index in range(parsed.count)]
    samples = []
    objective_ok = False
    state_path = output / 'window.json'
    with window(deploy, state_path) as state:
        for case_index, (subject, seed) in enumerate(cases):
            if active_competitors(deploy, state['definitions']):
                raise RuntimeError('production_gpu_task_arrived')
            payload = subject['path'].read_bytes()
            # 上传是原始字节接口（deploy.request 只发 JSON），这里单独用 urllib。
            import urllib.request
            token = deploy.values(deploy.ENV)['VIDEO_GENERATION_INTERNAL_TOKEN']
            request = urllib.request.Request(
                'http://127.0.0.1:23341/internal/v1/videos/uploads/image', data=payload,
                headers={'Authorization': 'Bearer ' + token, 'X-Owner-Id': '1',
                         'Content-Type': 'image/png'}, method='POST')
            with urllib.request.urlopen(request, timeout=180) as reply:
                upload_id = json.loads(reply.read())['id']
            code, job, _ = deploy.request(23341, '/internal/v1/videos/jobs', 'POST', {
                'resourceId': 'wan-vace-1.3b-local', 'mode': 'FIRST_FRAME', 'prompt': PROMPT,
                'inputs': [{'uploadId': upload_id, 'role': 'FIRST_FRAME'}],
                'output': {'size': '832x480', 'frames': 49, 'fps': 16}, 'seed': seed,
                'idempotencyKey': f"accept-{subject['slug']}-{seed}-{case_index}-{int(time.time())}"})
            if code != 200:
                samples.append({'subject': subject['label'], 'seed': seed, 'status': 'CREATE_FAILED',
                                'detail': json.dumps(job)[:300]})
                # 失败也要落盘并打印：否则窗口跑完只剩一句"全失败"，无从查因。
                (output / 'acceptance.json').write_text(
                    json.dumps({'samples': samples}, ensure_ascii=False, indent=2) + '\n')
                print(f"[create-failed] {subject['label']} seed={seed} code={code} "
                      f"{json.dumps(job, ensure_ascii=False)[:200]}", flush=True)
                raise RuntimeError('acceptance_create_failed')
            state['jobId'] = job['id']
            write_state(state_path, state)
            result = wait_window_job(deploy, job['id'], state['definitions'])
            entry = {'subject': subject['label'], 'seed': seed, 'jobId': job['id'],
                     'status': result['status'], 'errorCode': result.get('errorCode'),
                     'elapsedMillis': result.get('elapsedMillis'),
                     'result': result.get('result')}
            if result['status'] == 'SUCCEEDED':
                code, video, _ = deploy.request(23341, '/internal/v1/videos/jobs/' + job['id'] + '/video')
                if code != 200 or not isinstance(video, bytes):
                    raise RuntimeError('video_download_failed')
                target = output / f"{case_index:02d}-{subject['slug']}-seed{seed}.mp4"
                target.write_bytes(video)
                entry['video'] = str(target)
                entry['edges'] = deploy.edge_profile(target, SAMPLED_FRAMES)
                entry['motion'] = motion(target)
                entry['contentDrift'] = content_drift(target, SAMPLED_FRAMES)
                for index in SAMPLED_FRAMES:
                    frame_png(target, index, output / f"{case_index:02d}-{subject['slug']}-seed{seed}-f{index:02d}.png")
            state['jobId'] = None
            write_state(state_path, state)
            samples.append(entry)
            (output / 'acceptance.json').write_text(
                json.dumps({'samples': samples}, ensure_ascii=False, indent=2) + '\n')
            print(f"[{'ok' if entry['status'] == 'SUCCEEDED' else entry['status']}] {subject['label']} "
                  f"seed={seed} job={entry['jobId']} motion={entry.get('motion')} "
                  f"cast={json.dumps((entry.get('contentDrift') or {}).get('maxAbsCast'), ensure_ascii=False)} "
                  f"edges={json.dumps(entry.get('edges', []), ensure_ascii=False)[:160]}",
                  flush=True)
        # 客观判据：全部成功、且每条的补边都是中性灰。
        # 客观判据：全部成功、补边中性灰、内容区色偏在限内、运动量在"sane 区间"（不是静止也不是乱跳）。
        objective_ok = (len(samples) == len(cases)
                        and all(item.get('status') == 'SUCCEEDED' for item in samples)
                        and all(edges_are_neutral(deploy, item.get('edges') or []) for item in samples)
                        and all((item.get('contentDrift') or {}).get('maxAbsCast', 999) <= CONTENT_CAST_LIMIT
                                for item in samples)
                        and all(MOTION_FLOOR <= (item.get('motion') or 0) <= MOTION_CEILING for item in samples))
    summary = {'samples': len(samples), 'requestedCount': len(cases), 'humanReview': 'pending',
               'succeeded': sum(1 for item in samples if item.get('status') == 'SUCCEEDED'),
               'edgesNeutral': all(edges_are_neutral(deploy, item['edges'])
                                   for item in samples if item.get('edges')),
               'maxContentCast': max((item.get('contentDrift') or {}).get('maxAbsCast', 0)
                                     for item in samples) if samples else None,
               'objectiveVerdict': 'accept' if objective_ok else 'reject',
               'subjects': [subject['slug'] for subject in subjects], 'seeds': list(SEEDS),
               'output': str(output)}
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if objective_ok else 1


if __name__ == '__main__':
    sys.exit(main())
