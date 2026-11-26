#!/usr/bin/env python3
"""用当前执行租约完成手动周报，配置来自包环境，参数只含派发标识。"""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止工作令牌通过跳转发送到其他地址。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """所有跳转都使本次执行失败。"""
        raise RuntimeError('WEEKLY_REDIRECT_REJECTED')


def canonical_uuid(value):
    """拒绝非规范标识和目录穿越。"""
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('WEEKLY_CONTEXT_INVALID')
    return value


class Client:
    """请求固定服务地址，每次读取最新租约，不复用启动时的租约快照。"""

    def __init__(self, context, base, token, execution, lease_file):
        parsed = urllib.parse.urlsplit(base)
        secure = parsed.scheme == 'https' or (parsed.scheme == 'http' and parsed.hostname in {'127.0.0.1', '::1', 'localhost'})
        if not secure or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment \
                or parsed.path not in {'', '/'} or any(c.isspace() for c in base) or len(token) < 32:
            raise ValueError('WEEKLY_CONFIG_INVALID')
        if context.get('taskName') != 'news_generate_weekly' or context.get('stepName') != 'generate' \
                or context.get('executionId') != execution or set(context.get('parameters', {})) != {'jobId'}:
            raise ValueError('WEEKLY_CONTEXT_INVALID')
        self.dispatch = canonical_uuid(context['parameters']['jobId'])
        self.execution = canonical_uuid(execution)
        self.base, self.token, self.lease_file = base.rstrip('/'), token, Path(lease_file)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def post(self, action, body):
        """响应有界读取，任何授权或传输错误直接结束执行等待安全重试。"""
        if action not in {'context', 'progress', 'assets', 'report'}:
            raise ValueError('WEEKLY_ACTION_INVALID')
        lease = canonical_uuid(self.lease_file.read_text().strip())
        binary = isinstance(body, bytes)
        data = body if binary else json.dumps(body, ensure_ascii=False).encode()
        if len(data) > 8_000_000:
            raise ValueError('WEEKLY_REQUEST_TOO_LARGE')
        request = urllib.request.Request(f'{self.base}/internal/jobs/news/weekly/{self.dispatch}/{action}', data=data,
            headers={'Authorization': 'Bearer ' + self.token, 'X-Task-Execution-Id': self.execution,
                     'X-Task-Lease-Token': lease, 'Content-Type': 'application/octet-stream' if binary else 'application/json'}, method='POST')
        try:
            with self.opener.open(request, timeout=30) as response:
                payload = response.read(16385)
                if response.status != 200 or len(payload) > 16384:
                    raise RuntimeError('WEEKLY_RESPONSE_INVALID')
            value = json.loads(payload)
            if not isinstance(value, dict):
                raise RuntimeError('WEEKLY_RESPONSE_INVALID')
            return value
        except (OSError, urllib.error.URLError, ValueError):
            # 不把响应或异常字符串写到 Executor 日志，其中可能含内部地址或令牌。
            raise RuntimeError('WEEKLY_SERVICE_UNAVAILABLE') from None

    def context(self):
        """服务端固定窗口和任务身份，派发标识必须与请求一致。"""
        result = self.post('context', {})
        canonical_uuid(result.get('jobId'))
        if result.get('dispatchId') != self.dispatch or result.get('status') not in {'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'}:
            raise ValueError('WEEKLY_CONTEXT_INVALID')
        return result


def verify_runtime(root):
    """导入运行脚本前验证发布清单，固定路径只能指向不可变包。"""
    root = root.resolve(strict=True)
    manifest = json.loads((root / 'runtime-manifest.json').read_text())
    files = manifest.get('files', {})
    if not files or 'scripts/weekly_job.py' not in files:
        raise ValueError('WEEKLY_RUNTIME_INVALID')
    for name, digest in files.items():
        path = root / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root) \
                or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('WEEKLY_RUNTIME_INVALID')
    return root


def original_image(input_root, picture):
    """只压缩采集得到的原图，不生成新内容，拒绝越界路径和图片炸弹。"""
    from PIL import Image
    relative = picture.get('path', '')
    if not isinstance(relative, str) or not re.fullmatch(r'images/[a-z0-9_-]+\.(jpg|jpeg|png|webp)', relative):
        raise ValueError('WEEKLY_IMAGE_INVALID')
    path = input_root / relative
    if path.is_symlink() or (input_root / 'images').is_symlink() or not path.is_file() \
            or path.resolve().parent != (input_root / 'images').resolve() or path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('WEEKLY_IMAGE_INVALID')
    with Image.open(path) as source:
        if source.width * source.height > 32_000_000 or source.format not in {'JPEG', 'PNG', 'WEBP'}:
            raise ValueError('WEEKLY_IMAGE_INVALID')
        source.thumbnail((1280, 1280))
        image = source.convert('RGB')
        try:
            output = io.BytesIO()
            image.save(output, format='JPEG', quality=84, optimize=True)
            if output.tell() > 1_048_576:
                raise ValueError('WEEKLY_IMAGE_TOO_LARGE')
            return output.getvalue()
        finally:
            image.close()


def publish_images(client, report, input_root):
    """只有图片自身不可用时才降级纯文本，授权丢失必须终止整个工作进程。"""
    for section in report['sections']:
        for article in section['items']:
            picture = article.pop('image', None)
            if not picture:
                continue
            try:
                image = original_image(input_root, picture)
            except (ValueError, OSError):
                continue
            receipt = client.post('assets', image)
            asset = receipt.get('assetId', '')
            if not re.fullmatch(r'[a-f0-9]{64}', asset):
                raise RuntimeError('WEEKLY_ASSET_RECEIPT_INVALID')
            article['image'] = {'assetId': asset, 'caption': picture.get('caption', ''), 'sourceUrl': picture['sourceUrl']}


def write_result(result, target):
    """结果只记录报告回执，原子替换避免执行中断产生半文件。"""
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', dir=target.parent, encoding='utf-8', delete=False) as handle:
        json.dump(result, handle, separators=(',', ':'))
        temporary = Path(handle.name)
    temporary.replace(target)


def execute(client, context, runtime, config, work_root, runner):
    """报告和材料按稳定任务身份保存，新租约和失败重试共用已有检查点。"""
    job = canonical_uuid(context['jobId'])
    if context['status'] == 'RUNNING':
        directory = work_root / job
        if directory.is_symlink():
            raise ValueError('WEEKLY_DIRECTORY_INVALID')
        report = runner(config, runtime, directory, context['windowEnd'], lambda progress: client.post('progress', progress), attempt=context['attempt'])
        if report['windowStart'] != context['windowStart'] or report['windowEnd'] != context['windowEnd']:
            # ISO 格式允许 UTC Z 与 +00:00 等价；不允许改变时间窗。
            from datetime import datetime
            same = lambda a, b: datetime.fromisoformat(a.replace('Z', '+00:00')) == datetime.fromisoformat(b.replace('Z', '+00:00'))
            if not same(report['windowStart'], context['windowStart']) or not same(report['windowEnd'], context['windowEnd']):
                raise ValueError('WEEKLY_WINDOW_CHANGED')
        publish_images(client, report, directory / 'input')
        result = client.post('report', report)
    else:
        result = context
    if result.get('jobId') != job or result.get('dispatchId') != client.dispatch \
            or result.get('status') not in {'SUCCEEDED', 'PARTIAL', 'FAILED'} or not isinstance(result.get('reportRevision'), int):
        raise ValueError('WEEKLY_RECEIPT_INVALID')
    return {'jobId': job, 'status': result['status'], 'reportRevision': result['reportRevision']}


def main():
    """执行环境由专用 Executor 配置提供，不接受用户输入命令或路径。"""
    os.umask(0o077)
    task = json.loads(Path(os.environ['TASK_CONTEXT_FILE']).read_text())
    client = Client(task, os.environ['NEWS_SERVICE_URL'], os.environ['NEWS_JOB_TOKEN'],
                    os.environ['TASK_EXECUTION_ID'], os.environ['TASK_LEASE_TOKEN_FILE'])
    context = client.context()
    runtime = verify_runtime(Path(os.environ['NEWS_WEEKLY_RUNTIME']))
    sys.path.insert(0, str(runtime / 'scripts'))
    from weekly_job import run
    result = execute(client, context, runtime, Path(os.environ['NEWS_WEEKLY_CONFIG']), Path(os.environ['NEWS_WEEKLY_WORK_ROOT']), run)
    write_result(result, os.environ['TASK_RESULT_FILE'])


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('NEWS_WEEKLY_EXECUTION_FAILED', file=sys.stderr)
        sys.exit(1)
