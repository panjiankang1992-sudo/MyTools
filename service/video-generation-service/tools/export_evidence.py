#!/usr/bin/env python3
"""把运行时证据导出为 acceptance.md 要求的交付目录，供人工评分与归档。

导出只搬运已产生的原始证据并汇总数值，不修改任何原始产物，也不替人工填写评分：
reviews.json 生成后所有条目为 pending，必须由人工看过首/中/末帧与完整播放后填写。
"""

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def read_json(path, default=None):
    """读取 JSON，文件缺失或不可解析时回退到默认值。"""
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return default


def digest(path):
    """计算文件 SHA-256。"""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def cgroup_value(measurement, key):
    """从 systemctl 输出片段中取指定属性。"""
    for line in measurement.get('cgroup', '').splitlines():
        if line.startswith(key + '='):
            return int(line.split('=', 1)[1])
    return None


def peak_worker_mib(metrics):
    """取窗口内 worker 单元的最高内存峰值，systemd 以字节报告。"""
    values = [cgroup_value(measurement, 'MemoryPeak') or 0 for measurement in metrics]
    return round(max(values, default=0) / 1024 ** 2, 1)


def case_record(run_dir, index):
    """把单次运行汇总成一条 cases.jsonl 记录。"""
    result = read_json(run_dir / 'result.json', {})
    metrics = [json.loads(line) for line in (run_dir / 'metrics.jsonl').read_text().splitlines()] if (run_dir / 'metrics.jsonl').exists() else []
    review = read_json(run_dir / 'review-metrics.json', {})
    probe = read_json(run_dir / 'ffprobe.json', {})
    stream = (probe.get('streams') or [{}])[0]
    peaks = {
        'workerMemoryPeakMiB': peak_worker_mib(metrics),
        'gpuUsedMaxMiB': max((int(measurement['gpu'].split(',')[0]) for measurement in metrics), default=None),
        'hostAvailableMinMiB': min((measurement['availableMiB'] for measurement in metrics), default=None),
    }
    return {
        'caseId': result.get('runId'),
        'index': index,
        'workflow': result.get('workflow'),
        'status': result.get('status'),
        'warmStart': result.get('warmStart'),
        'preflight': result.get('preflight'),
        'errorType': result.get('errorType'),
        'error': result.get('error'),
        'workflowSha256': result.get('workflowSha256'),
        'outputSha256': result.get('outputSha256'),
        'submittedAt': result.get('submittedAt'),
        'finishedAt': result.get('finishedAt'),
        'inferenceSeconds': round(result['finishedAt'] - result['submittedAt'], 1) if result.get('submittedAt') and result.get('finishedAt') else None,
        'output': {'codec': stream.get('codec_name'), 'width': stream.get('width'), 'height': stream.get('height'),
                   'fps': stream.get('avg_frame_rate'), 'frames': stream.get('nb_frames'),
                   'durationSeconds': float(probe.get('format', {}).get('duration') or 0) or None},
        'peaks': peaks,
        'objectiveMetrics': review,
    }


def copy_inputs(source_root, target):
    """复制本次使用的输入素材并记录来源与哈希。"""
    folder = target / 'inputs'
    folder.mkdir(parents=True, exist_ok=True)
    manifest = []
    for path in sorted(source_root.iterdir()):
        if path.is_file() and path.name.startswith('p0-'):
            shutil.copy2(path, folder / path.name)
            manifest.append({'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)})
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def copy_runs(window, target, cases):
    """复制每个运行的产物、关键帧、原始指标与工作流。"""
    for case, run_dir in zip(cases, sorted(path for path in window.glob('run-*'))):
        output = target / 'outputs' / case['caseId']
        output.mkdir(parents=True, exist_ok=True)
        wanted = ('output.mp4', 'workflow.json', 'ffprobe.json', 'review-metrics.json', 'result.json',
                  'inputs.json', 'keyframe-00.png', 'keyframe-24.png', 'keyframe-48.png',
                  'keyframe-mid.png', 'keyframe-last.png', 'contact-sheet.jpg')
        for name in wanted:
            if (run_dir / name).exists():
                shutil.copy2(run_dir / name, output / name)
        metrics = target / 'metrics' / case['caseId']
        metrics.mkdir(parents=True, exist_ok=True)
        for name in ('metrics.jsonl', 'host-before.json', 'host-after.json'):
            if (run_dir / name).exists():
                shutil.copy2(run_dir / name, metrics / name)


def copy_storyboard(window, target):
    """复制分镜拼接产物；分镜是窗口级结果，不属于任何单个镜头。"""
    source = window / 'storyboard'
    if not source.is_dir():
        return None
    folder = target / 'storyboard'
    folder.mkdir(parents=True, exist_ok=True)
    for name in ('storyboard.mp4', 'manifest.json', 'review-metrics.json',
                 'keyframe-00.png', 'keyframe-mid.png', 'keyframe-last.png', 'contact-sheet.jpg'):
        if (source / name).exists():
            shutil.copy2(source / name, folder / name)
    return read_json(source / 'manifest.json')


def main():
    """组装交付目录，并写出人工评分模板与汇总报告。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence-root', required=True)
    parser.add_argument('--window', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--models-lock')
    args = parser.parse_args()
    root = Path(args.evidence_root)
    window = root / args.window
    target = Path(args.target)
    target.mkdir(parents=True, exist_ok=True)
    summary = read_json(window / 'summary.json', {'results': []})
    cases = [case_record(run_dir, index) for index, run_dir in enumerate(sorted(window.glob('run-*')), start=1)]
    copy_runs(window, target, cases)
    storyboard = copy_storyboard(window, target)
    manifest = {
        'windowId': args.window,
        'runtime': read_json(root / 'runtime.json'),
        'weights': read_json(root / 'models.json'),
        'modelsLock': read_json(Path(args.models_lock)) if args.models_lock else None,
        'plan': read_json(window / 'plan.json'),
        'schemaSha256': digest(window / 'schema.json') if (window / 'schema.json').exists() else None,
        'admission': read_json(window / 'host-before.json', {}).get('admission'),
        'caseCount': len(cases),
        'passed': sum(1 for case in cases if case['status'] == 'INFERENCE_PASSED_REVIEW_PENDING'),
        'failed': sum(1 for case in cases if case['status'] == 'FAILED'),
        'storyboard': {'expected': storyboard['expected'], 'result': storyboard['result'],
                       'shots': storyboard['shots']} if storyboard else None,
    }
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (target / 'cases.jsonl').write_text(''.join(json.dumps(case, ensure_ascii=False) + '\n' for case in cases))
    (target / 'host-before.json').write_text(json.dumps(read_json(window / 'host-before.json', {}), indent=2) + '\n')
    (target / 'host-after.json').write_text(json.dumps(read_json(window / 'host-after.json', {}), indent=2) + '\n')
    copy_inputs(Path(args.evidence_root).parent / 'runtime-v1/ComfyUI/input', target)
    reviews = {'windowId': args.window, 'scoring': 'acceptance.md：指令遵循/主体保持/时间连贯/编辑局部性/伪影，各 1-5 分',
               'status': 'PENDING_HUMAN_REVIEW', 'cases': [
                   {'caseId': case['caseId'], 'status': 'pending', 'reviewedKeyframes': False,
                    'playedTwice': False, 'instructionFollowing': None, 'subjectPreservation': None,
                    'temporalCoherence': None, 'editLocality': None, 'artifacts': None, 'defects': ''}
                   for case in cases]}
    (target / 'reviews.json').write_text(json.dumps(reviews, indent=2, ensure_ascii=False) + '\n')
    (target / 'report.md').write_text(render_report(manifest, cases))
    (target / 'health-after.json').write_text(json.dumps({'windowSummary': summary, 'hostAfter': read_json(window / 'host-after.json', {})}, indent=2) + '\n')
    print(json.dumps({'target': str(target), 'cases': len(cases), 'passed': manifest['passed'], 'failed': manifest['failed']}), flush=True)


def render_report(manifest, cases):
    """生成只包含实测数字与待办项的报告骨架，不预写验收结论。"""
    lines = ['# P0 视频生成验证报告', '',
             f'窗口：`{manifest["windowId"]}`；通过 {manifest["passed"]} 项、失败 {manifest["failed"]} 项。', '',
             '本报告只汇总实测数据。人工评分（指令遵循、主体保持、时间连贯、伪影）见 reviews.json，'
             '在人工填写前，所有模式都不得标记为"已验证"。', '',
             '## 运行与资源实测', '',
             '| 用例 | 状态 | 冷/热启动 | 推理耗时(s) | 帧数 | 峰值 worker RAM(MiB) | 峰值显存(MiB) | 最低可用内存(MiB) |',
             '|---|---|---|---|---|---|---|---|']
    for case in cases:
        peaks = case['peaks']
        lines.append('| {} | {} | {} | {} | {} | {} | {} | {} |'.format(
            case['caseId'], case['status'], '热' if case['warmStart'] else '冷',
            case['inferenceSeconds'], case['output']['frames'], peaks['workerMemoryPeakMiB'],
            peaks['gpuUsedMaxMiB'], peaks['hostAvailableMinMiB']))
    lines += ['', '## 客观指标（仅用于发现硬故障，不替代人工评分）', '',
              '| 用例 | 帧间运动均值 | 冻结帧对 | 全黑帧 | 纯色帧 | 首末帧差异 |',
              '|---|---|---|---|---|---|']
    for case in cases:
        metric = case.get('objectiveMetrics') or {}
        motion = metric.get('motion') or {}
        lines.append('| {} | {} | {} | {} | {} | {} |'.format(
            case['caseId'], motion.get('mean'), motion.get('frozenPairs'),
            metric.get('blackFrames'), metric.get('flatFrames'), metric.get('firstLastDifference')))
    lines += ['', '## 待人工完成', '',
              '1. 逐条查看 outputs/<caseId>/output.mp4 的首/中/末关键帧，并完整播放两遍。',
              '2. 在 reviews.json 中填写五项评分与具体缺陷描述。',
              '3. 只有某项在 6 组样例中各关键项均达标且无严重缺陷，才可把该 mode/model/workflow 标记为已验证。']
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    main()
