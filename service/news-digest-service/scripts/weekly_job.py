#!/usr/bin/env python3
"""手动任务的采集与分析入口，所有执行身份由可信工作进程提供。"""
import argparse
import copy
from datetime import datetime, timezone
import fcntl
import hashlib
import json
from pathlib import Path
import re
import shutil

from personal_daily import collect, load_config
from weekly_report import generate, instant, save
from weekly_sources import backfill, copy_image


def retry_source(config, runtime, directory, end, attempt, previous, progress):
    """仅重采来源失败分类，新输入独立冻结，已完成报告作为稳定去重锚点。"""
    target = directory / 'input' / f'retry-{attempt}.json'
    if target.exists():
        return target
    # 后一代次继承上一代已补采的材料，不能退回首次失败输入。
    base = next((directory / 'input' / f'retry-{prior}.json' for prior in range(attempt - 1, 1, -1)
                 if (directory / 'input' / f'retry-{prior}.json').exists()), directory / 'input/report-input.json')
    data = json.loads(base.read_text())
    failed = {row['category'] for row in previous['failedSections']}
    refresh = {row['category'] for row in previous['failedSections']
               if row['code'] in {'WEEKLY_SOURCES_UNAVAILABLE', 'WEEKLY_REGION_UNAVAILABLE'}}
    if refresh:
        staging = directory / f'collection-retry-{attempt}'
        staging.mkdir(exist_ok=True, mode=0o700)
        if not (staging / 'input/report-input.json').exists():
            if progress:
                progress({'stage': 'COLLECTING', 'category': None, 'completedCategories': len(previous['sections'])})
            collect(runtime, config, staging, weekly_cutoff=end.isoformat(), weekly_categories=refresh)
        source = staging / 'input/report-input.json'
        fresh = json.loads(source.read_text())
        if instant(fresh['cutoff']) != end:
            raise ValueError('WEEKLY_WINDOW_CHANGED')
        replacement = {section['category']: section for section in fresh['sections'] if section['category'] in refresh}
        for section in replacement.values():
            for item in section['items']:
                copy_image(item, source, target)
        data['sections'] = [replacement.get(section['category'], section) for section in data['sections']]
        data['collection'] = [row for row in data.get('collection', []) if row.get('category') not in refresh] \
            + [row for row in fresh.get('collection', []) if row.get('category') in refresh]
        if 'LOCAL' in refresh:
            data['region'] = fresh.get('region', {})
    data['retainedSections'] = copy.deepcopy(previous['sections'])
    data['retryAttempt'] = attempt
    # 仅保留失败分类候选；成功分类会由最终报告锚点替换，避免重复调用模型。
    for section in data['sections']:
        if section['category'] not in failed:
            section['items'] = []
    save(target, data)
    return target


def cleanup(root, now):
    """下一次手动运行时清理九十天前的专属任务缓存，不碰旧日报和 DSH 会话。"""
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    for path in root.iterdir():
        identity = path / 'job-input.json'
        if path.is_symlink() or not path.is_dir() or not re.fullmatch(r'[a-f0-9-]{36}', path.name) \
                or identity.is_symlink() or not identity.is_file():
            continue
        try:
            frozen = json.loads(identity.read_text())
            if (now - instant(frozen['cutoff'])).total_seconds() <= 90 * 86400:
                continue
            lock_path = path / '.job.lock'
            if lock_path.is_symlink():
                continue
            with lock_path.open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                shutil.rmtree(path)
        except (OSError, ValueError, KeyError):
            continue


def runtime_hash(runtime):
    """逐文件验证不可变发布清单，拒绝缺文件、越界路径或内容被替换的包。"""
    manifest = runtime / 'runtime-manifest.json'
    entries = json.loads(manifest.read_text()).get('files', {})
    if not entries:
        raise ValueError('WEEKLY_RUNTIME_INVALID')
    for name, expected in entries.items():
        path = runtime / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(runtime.resolve()) \
                or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('WEEKLY_RUNTIME_INVALID')
    return hashlib.sha256(manifest.read_bytes()).hexdigest()


def run(config_path, runtime, directory, cutoff, progress=None, attempt=1):
    """冻结任务窗口和运行包，在独立任务目录恢复，不调用邮件或 RSS 发布。"""
    end = instant(cutoff)
    if type(attempt) is not int or not 1 <= attempt <= 10:
        raise ValueError('WEEKLY_ATTEMPT_INVALID')
    if end > datetime.now(timezone.utc):
        raise ValueError('WEEKLY_WINDOW_INVALID')
    config = load_config(config_path)
    cleanup(directory.parent, datetime.now(timezone.utc))
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    identity = {'cutoff': end.isoformat(), 'runtimeHash': runtime_hash(runtime),
                'configHash': hashlib.sha256(config_path.read_bytes()).hexdigest()}
    with (directory / '.job.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        frozen = directory / 'job-input.json'
        if frozen.exists():
            previous = json.loads(frozen.read_text())
            if any(previous.get(key) != identity[key] for key in ('cutoff', 'configHash')):
                raise ValueError('WEEKLY_INPUT_CHANGED')
            previous_attempt = previous.get('runtimeAttempt', 1)
            changed = previous.get('runtimeHash') != identity['runtimeHash']
            if attempt < previous_attempt or (changed and attempt <= previous_attempt):
                raise ValueError('WEEKLY_INPUT_CHANGED')
            # 仅用户发起的新代次允许采用已校验的新运行包；同代次恢复仍固定原版本。
            history = previous.get('runtimeHistory', [{'attempt': previous_attempt, 'hash': previous['runtimeHash']}])
            if changed:
                history.append({'attempt': attempt, 'hash': identity['runtimeHash']})
            identity['runtimeHistory'] = history
        identity['runtimeAttempt'] = attempt
        save(frozen, identity)
        if not (directory / 'input').exists():
            state = {'stage': 'COLLECTING', 'category': None, 'completedCategories': 0}
            save(directory / 'progress.json', state)
            if progress:
                progress(state)
            collect(runtime, config, directory, weekly_cutoff=end.isoformat())
        # 第一次冻结输入前复用近七天已存正文；失败恢复不重新混入变化的缓存。
        backfill(config, directory)
        source, output = directory / 'input/report-input.json', directory / 'report'
        latest = directory / 'latest-report.json'
        retry_input = directory / 'input' / f'retry-{attempt}.json'
        if attempt > 1 and retry_input.exists():
            # 发布回执丢失时继续相同代次，不能重新生成首次失败报告。
            source, output = retry_input, directory / f'report-retry-{attempt}'
        elif attempt > 1 and latest.exists():
            previous = json.loads(latest.read_text())
            if previous['failedSections']:
                source = retry_source(config, runtime, directory, end, attempt, previous, progress)
                output = directory / f'report-retry-{attempt}'
        result = generate(source, output, config.get('dsh', 'dsh'), config.get('analysisTimeoutSeconds', 300), progress)
        save(latest, result)
        return result


def main():
    """命令行仅用于手动执行和工作进程适配，不安装自动定时器。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--cutoff', required=True)
    args = parser.parse_args()
    result = run(args.config, args.runtime, args.directory, args.cutoff)
    print(json.dumps({'status': result['status'], 'categories': len(result['sections'])}))


if __name__ == '__main__':
    main()
