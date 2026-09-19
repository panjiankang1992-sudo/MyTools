#!/usr/bin/env python3
"""首帧模式的验收窗口：多素材 × 多种子跑真实任务，量测补边是否仍是中性灰。

窗口动作与 `deploy_video_generation.py verify` 一致：暂停含 GPU 步骤的任务定义、排空在途任务、
只打开候选模式（网关路由保持关闭，用户提交不进来），结束时无论成败都恢复原状。

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

DEPLOY = Path(__file__).resolve().parent / 'deploy_video_generation.py'
SEEDS = (42, 932)
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--subjects', nargs='+', required=True,
                        help='素材路径:标签，例如 /tmp/a.png:冷白底')
    parser.add_argument('--output', required=True, help='证据目录')
    parsed = parser.parse_args()
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

    # 窗口：暂停 GPU 竞争者并排空，随后只打开首帧模式。
    with deploy.task_connection() as db, db.cursor() as cursor:
        cursor.execute('SELECT DISTINCT td.id, td.name, td.max_concurrency FROM task_definition td '
                       'JOIN task_step_definition ts ON ts.task_definition_id=td.id '
                       'WHERE ts.script_package IN (%s,%s) FOR UPDATE', deploy.GPU_PACKAGES)
        definitions = [{'id': row[0], 'name': row[1], 'capacity': row[2]} for row in cursor.fetchall()]
        for row in definitions:
            cursor.execute('UPDATE task_definition SET max_concurrency=0 WHERE id=%s', (row['id'],))
        db.commit()
        ids = [row['id'] for row in definitions]
        placeholders = ','.join(['%s'] * len(ids))
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            with db.cursor() as check:
                check.execute(f'SELECT COUNT(*) FROM task_instance WHERE task_definition_id IN ({placeholders}) '
                              "AND status IN ('RUNNING','CANCELLING','QUEUED')", ids)
                if check.fetchone()[0] == 0:
                    break
            time.sleep(5)
        else:
            raise SystemExit('production_gpu_tasks_active')
    samples = []
    objective_ok = False
    try:
        deploy.set_mode_flags(['FIRST_FRAME'])
        for subject in subjects:
            payload = subject['path'].read_bytes()
            for seed in SEEDS:
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
                    'idempotencyKey': f"accept-{subject['slug']}-{seed}-{int(time.time())}"})
                if code != 200:
                    samples.append({'subject': subject['label'], 'seed': seed, 'status': 'CREATE_FAILED',
                                    'detail': json.dumps(job)[:300]})
                    # 失败也要落盘并打印：否则窗口跑完只剩一句"全失败"，无从查因。
                    (output / 'acceptance.json').write_text(
                        json.dumps({'samples': samples}, ensure_ascii=False, indent=2) + '\n')
                    print(f"[create-failed] {subject['label']} seed={seed} code={code} "
                          f"{json.dumps(job, ensure_ascii=False)[:200]}", flush=True)
                    continue
                result = deploy.wait_job(job['id'])
                entry = {'subject': subject['label'], 'seed': seed, 'jobId': job['id'],
                         'status': result['status'], 'errorCode': result.get('errorCode'),
                         'elapsedMillis': result.get('elapsedMillis'),
                         'result': result.get('result')}
                if result['status'] == 'SUCCEEDED':
                    code, video, _ = deploy.request(23341, '/internal/v1/videos/jobs/' + job['id'] + '/video')
                    target = output / f"{subject['slug']}-seed{seed}.mp4"
                    target.write_bytes(video)
                    entry['video'] = str(target)
                    entry['edges'] = deploy.edge_profile(target, SAMPLED_FRAMES)
                    entry['motion'] = motion(target)
                    for index in SAMPLED_FRAMES:
                        frame_png(target, index, output / f"{subject['slug']}-seed{seed}-f{index:02d}.png")
                samples.append(entry)
                (output / 'acceptance.json').write_text(
                    json.dumps({'samples': samples}, ensure_ascii=False, indent=2) + '\n')
                print(f"[{'ok' if entry['status'] == 'SUCCEEDED' else entry['status']}] {subject['label']} "
                      f"seed={seed} job={entry['jobId']} edges={json.dumps(entry.get('edges', []), ensure_ascii=False)[:200]}",
                      flush=True)
        # 客观判据：全部成功、且每条的补边都是中性灰。
        objective_ok = (len(samples) == len(subjects) * len(SEEDS)
                        and all(item.get('status') == 'SUCCEEDED' for item in samples)
                        and all(edges_are_neutral(deploy, item.get('edges') or []) for item in samples))
    finally:
        # 收尾：恢复容量；模式开关按客观判据决定——全绿才继续开放，否则关掉等人工处理。
        with deploy.task_connection() as db, db.cursor() as cursor:
            for row in definitions:
                cursor.execute('UPDATE task_definition SET max_concurrency=%s WHERE id=%s AND max_concurrency=0',
                               (row['capacity'], row['id']))
            db.commit()
        deploy.set_mode_flags(['FIRST_FRAME'] if objective_ok else [])
    summary = {'samples': len(samples),
               'succeeded': sum(1 for item in samples if item.get('status') == 'SUCCEEDED'),
               'edgesNeutral': all(edges_are_neutral(deploy, item['edges'])
                                   for item in samples if item.get('edges')),
               'objectiveVerdict': 'accept' if objective_ok else 'reject',
               'subjects': [subject['slug'] for subject in subjects], 'seeds': list(SEEDS),
               'output': str(output)}
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
