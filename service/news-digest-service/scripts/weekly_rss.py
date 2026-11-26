#!/usr/bin/env python3
"""将指定自用账号已验收的手动周报发布为 RSS，不触发采集、模型或邮件。"""
import argparse
import base64
from datetime import datetime, timedelta
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
import uuid

from personal_report import CATEGORIES
from public_news import public_url
from publish_rss import configuration, durable_write, publish


def require(condition, code):
    """使用稳定错误码拒绝不完整快照，不把来源材料或凭据写入日志。"""
    if not condition:
        raise ValueError('RSS_WEEKLY_' + code)


def moment(value):
    """只接受带时区时间，避免不同服务时间口径混淆。"""
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'TIME_INVALID')
    return result


class NoRedirect(HTTPRedirectHandler):
    """内部访问禁止重定向，防止令牌离开固定本机服务。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """所有跳转均交由调用者按失败重试。"""
        return None


class Client:
    """仅允许固定回环地址和显式账号的内部只读请求。"""

    def __init__(self, base, owner, token):
        """拒绝代理、认证 URL 和非本机地址。"""
        require(bool(re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', base))
                and 0 < urlsplit(base).port < 65536, 'API_INVALID')
        require(isinstance(owner, int) and not isinstance(owner, bool) and owner > 0, 'OWNER_INVALID')
        require(bool(token) and '\n' not in token and '\r' not in token, 'TOKEN_MISSING')
        self.base, self.owner, self.token = base, owner, token
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def get(self, path, **params):
        """有界读取已提交 API，错误输出不包含响应正文。"""
        require(path.startswith('/weekly-reports') and '?' not in path and '#' not in path, 'PATH_INVALID')
        params['ownerId'] = self.owner
        request = Request(self.base + '/internal/v1/news' + path + '?' + urlencode(params),
                          headers={'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json'})
        with self.opener.open(request, timeout=20) as response:
            payload = response.read(2_000_001)
            require(response.status == 200 and len(payload) <= 2_000_000, 'RESPONSE_INVALID')
        return json.loads(payload)


def latest_report(client):
    """按窗口选择最新完成报告，旧任务晚完成不会倒退当前订阅。"""
    cursor = None
    for _ in range(100):
        params = {'limit': 100}
        if cursor:
            params['cursor'] = cursor
        history = client.get('/weekly-reports', **params)
        candidates = [row for row in history['items']
                      if row['status'] in {'SUCCEEDED', 'PARTIAL'} and row['reportRevision'] > 0]
        if candidates:
            return max(candidates, key=lambda row: (moment(row['windowEnd']), row['id']))
        cursor = history.get('nextCursor')
        if not cursor:
            return None
    raise ValueError('RSS_WEEKLY_HISTORY_TOO_LARGE')


def download_report(client, job, directory):
    """从同一验收修订读取完整文章与原图，前后修订变化时放弃暂存结果。"""
    job_id = str(uuid.UUID(job['id']))
    path = '/weekly-reports/' + job_id
    metadata = client.get(path)
    revision = job['reportRevision']
    require(metadata['revision'] == revision and metadata['status'] in {'SUCCEEDED', 'PARTIAL'}, 'REVISION_CHANGED')
    require(metadata['id'] == job_id and metadata['windowEnd'] == job['windowEnd'], 'IDENTITY_INVALID')
    require(moment(metadata['windowEnd']) - moment(metadata['windowStart']) == timedelta(days=7), 'WINDOW_INVALID')
    categories = [section['category'] for section in metadata['sections']]
    require(len(categories) == len(set(categories)) and set(categories) <= set(CATEGORIES), 'CATEGORIES_INVALID')
    images = directory / 'images'
    images.mkdir()
    report = {'mode': 'WEEKLY', 'publicationId': job_id + ':' + str(revision),
              'cutoff': metadata['windowEnd'], 'windowStart': metadata['windowStart'], 'zone': 'Asia/Shanghai',
              'region': {'regionCode': (metadata.get('region') or {}).get('regionCode', '')}, 'sections': []}
    events, downloaded = set(), {}
    for section in metadata['sections']:
        category = section['category']
        listing = client.get(path + '/categories/' + category)
        require(len(listing['items']) == section['count'] <= 20, 'COUNT_INVALID')
        exported = {'category': category, 'items': []}
        for rank, row in enumerate(listing['items'], 1):
            article = client.get(path + '/articles/' + quote(row['eventId'], safe=''))
            event = article['eventId']
            require(event == row['eventId'] and event not in events and article['rank'] == rank, 'EVENT_INVALID')
            events.add(event)
            require(len(article['summary']) >= 40 and 1 <= len(article['points']) <= 5, 'DETAIL_INVALID')
            links = list(dict.fromkeys(public_url(link) for link in article['sourceLinks']))
            require(0 < len(links) <= 32, 'SOURCES_INVALID')
            item = {'rank': rank, 'eventId': event, 'url': links[0], 'sourceLinks': links,
                    'detailAvailable': True, 'rssDetailStatus': 'ready',
                    'analysis': {key: article.get(key, '') for key in ('title', 'summary', 'points', 'limitation')}}
            picture = article.get('image')
            if picture:
                asset = picture['assetId']
                require(bool(re.fullmatch(r'[a-f0-9]{64}', asset)), 'ASSET_INVALID')
                if asset not in downloaded:
                    image = client.get(path + '/assets/' + asset)
                    payload = base64.b64decode(image['base64'], validate=True)
                    require(16 <= len(payload) <= 1_048_576 and hashlib.sha256(payload).hexdigest() == asset,
                            'ASSET_HASH_INVALID')
                    suffix = {'image/png': '.png', 'image/jpeg': '.jpg'}.get(image['mime'])
                    require(bool(suffix), 'ASSET_TYPE_INVALID')
                    downloaded[asset] = 'images/' + asset + suffix
                    (directory / downloaded[asset]).write_bytes(payload)
                source = public_url(picture['sourceUrl'])
                # 配图来源属于已验收文章；额外证据不挤掉用于稳定身份的代表原文。
                if source not in links:
                    item['sourceLinks'] = links[:31] + [source]
                item['image'] = {'path': downloaded[asset], 'sourceUrl': source, 'url': source,
                                 'kind': 'SOURCE', 'caption': picture.get('caption', '')}
            exported['items'].append(item)
        report['sections'].append(exported)
    require(len(events) == metadata['articleCount'], 'TOTAL_INVALID')
    require(client.get(path)['revision'] == revision, 'REVISION_CHANGED')
    target = directory / 'report.json'
    target.write_text(json.dumps(report, ensure_ascii=True))
    return target


def synchronize(config, client):
    """锁定消费水位；发布失败不前移，崩溃恢复后重复发布保持 GUID 与字节不变。"""
    rss = configuration(config['rss'])
    require(rss['enabled'] and rss['feedFormat'] == 'articles', 'PUBLISHING_DISABLED')
    state_dir = Path(config['stateDirectory'])
    require(state_dir.is_absolute() and not state_dir.is_symlink(), 'STATE_INVALID')
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state_dir / '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state_file = state_dir / 'state.json'
        state = json.loads(state_file.read_text()) if state_file.exists() else {}
        require(not state or state['ownerId'] == client.owner, 'OWNER_CHANGED')
        job = latest_report(client)
        if not job:
            return {'status': 'no_report'}
        watermark = (moment(job['windowEnd']), job['id'])
        if state and watermark < (moment(state['windowEnd']), state['jobId']):
            return {'status': 'older_ignored'}
        publication = job['id'] + ':' + str(job['reportRevision'])
        if state and state['publicationId'] == publication:
            current = Path(rss['directory']) / 'current'
            manifest = current.resolve().parent / 'manifest.json'
            if current.is_symlink() and manifest.is_file():
                existing = json.loads(manifest.read_text())
                if existing.get('policy', {}).get('weeklyRun') == publication and existing['baseUrl'] == rss['baseUrl']:
                    return {'status': 'unchanged'}
        with tempfile.TemporaryDirectory(prefix='snapshot-', dir=state_dir) as temporary:
            report = download_report(client, job, Path(temporary))
            result = publish(report, rss, region_input=report)
        require(result['status'] in {'published', 'published_partial', 'unchanged'} and not result.get('failedSections'),
                'PUBLISH_FAILED')
        durable_write(state_file, json.dumps({'ownerId': client.owner, 'publicationId': publication,
                      'jobId': job['id'], 'windowEnd': job['windowEnd'], 'release': result['release']}).encode(), 0o600)
        return {'status': result['status'], 'release': result['release']}


def main():
    """轮询只消费已有报告，不产生任何自动新闻任务；异常后下轮继续重试。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--watch', action='store_true')
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    client = Client(config['apiBaseUrl'], config['ownerId'], os.environ.get('NEWS_INTERNAL_TOKEN', ''))
    while True:
        try:
            result = synchronize(config, client)
        except Exception as error:
            code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'RSS_[A-Z_]+', str(error)) else type(error).__name__
            result = {'status': 'failed', 'code': code}
        if not args.watch or result['status'] not in {'unchanged', 'no_report', 'older_ignored'}:
            print(json.dumps(result), flush=True)
        if not args.watch:
            return 1 if result['status'] == 'failed' else 0
        time.sleep(30)


if __name__ == '__main__':
    raise SystemExit(main())
