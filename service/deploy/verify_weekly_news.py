#!/usr/bin/env python3
"""只读验收已完成的真实周报，凭据只在执行机内读取且不进入输出。"""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid

import pymysql


def verify(job_id):
    """查询真实报告、全部详情与原图，并验证非所有者和匿名访问边界。"""
    assert str(uuid.UUID(job_id)) == job_id, 'INVALID_JOB_ID'
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', database='mytools_news',
                         cursorclass=pymysql.cursors.DictCursor) as db, db.cursor() as cursor:
        cursor.execute('SELECT owner_id,status,attempt,report_revision,window_end_ms,progress_json FROM news_weekly_job WHERE id=%s', (job_id,))
        job = cursor.fetchone()
    assert job, 'JOB_NOT_FOUND'
    if job['status'] in {'QUEUED', 'RUNNING'}:
        return {'status': job['status'], 'progress': json.loads(job['progress_json'])}, 2
    assert job['report_revision'] > 0, 'REPORT_NOT_PUBLISHED'
    config = Path('/opt/yuyutian/mytools/config/weekly-news/service.env').read_text().splitlines()
    token = next(json.loads(line.split('=', 1)[1]) for line in config if line.startswith('NEWS_INTERNAL_TOKEN='))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def get(path, owner=None, expected=200, authenticated=True, port=23350):
        """仅请求固定本机地址；错误响应只记录状态，不回显请求或秘密。"""
        suffix = ('&' if '?' in path else '?') + 'ownerId=' + str(owner) if owner is not None else ''
        request = urllib.request.Request(f'http://127.0.0.1:{port}{path}{suffix}',
                                         headers={'Authorization': 'Bearer ' + token} if authenticated else {})
        try:
            with opener.open(request, timeout=10) as response:
                status, raw, headers = response.status, response.read(2_000_001), response.headers
        except urllib.error.HTTPError as error:
            status, raw, headers = error.code, b'{}', error.headers
        assert status == expected and len(raw) <= 2_000_000, 'HTTP_BOUNDARY_FAILED'
        return json.loads(raw), headers

    owner = job['owner_id']
    path = '/internal/v1/news/weekly-reports/' + job_id
    report, _ = get(path, owner)
    assert report['status'] == job['status'] and report['revision'] == job['report_revision'], 'REPORT_REVISION_INVALID'
    end = datetime.fromtimestamp(job['window_end_ms']/1000, timezone.utc)
    start = end - timedelta(days=7)
    parse = lambda value: datetime.fromisoformat(value.replace('Z', '+00:00'))
    assert parse(report['windowStart']) == start and parse(report['windowEnd']) == end, 'WINDOW_INVALID'
    categories = {s['category'] for s in report['sections']} | {s['category'] for s in report['failedSections']}
    assert len(categories) == 10, 'CATEGORY_COVERAGE_INVALID'
    events, assets, stats = set(), set(), []
    first_event = None
    first_category = None
    for section in report['sections']:
        category = section['category']
        first_category = first_category or category
        rows, _ = get(path+'/categories/'+category, owner)
        assert rows['count'] == len(rows['items']) == section['count'] <= 20, 'COUNT_INVALID'
        pictures = 0
        for index, row in enumerate(rows['items'], 1):
            article, _ = get(path+'/articles/'+row['eventId'], owner)
            event = article['eventId']
            assert event not in events and article['rank'] == index, 'DUPLICATE_EVENT'
            events.add(event)
            first_event = first_event or event
            assert len(article['summary']) >= 40 and 1 <= len(article['points']) <= 5 and article['sourceLinks'], 'DETAIL_INVALID'
            assert start <= parse(article.get('publishedAt') or article['activityAt']) < end, 'ARTICLE_TIME_INVALID'
            assert all(url.startswith('https://') for url in article['sourceLinks']), 'SOURCE_INVALID'
            if article.get('image'):
                pictures += 1
                asset_id = article['image']['assetId']
                if asset_id not in assets:
                    image, headers = get(path+'/assets/'+asset_id, owner)
                    content = base64.b64decode(image['base64'], validate=True)
                    assert hashlib.sha256(content).hexdigest() == asset_id and 16 <= len(content) <= 1_048_576, 'ASSET_HASH_INVALID'
                    assert image['mime'] in {'image/png', 'image/jpeg'} and 'no-store' in headers.get('Cache-Control', ''), 'ASSET_RESPONSE_INVALID'
                    assets.add(asset_id)
        stats.append({'category': category, 'articles': len(rows['items']), 'originalImages': pictures})
    assert len(events) == report['articleCount'], 'TOTAL_INVALID'
    denied = [path, path+'/categories/'+first_category] if first_category else [path]
    if first_event:
        denied.append(path+'/articles/'+first_event)
    if assets:
        denied.append(path+'/assets/'+next(iter(assets)))
    for denied_path in denied:
        get(denied_path, owner+1, expected=404)
    get(path, owner, expected=401, authenticated=False)
    get('/api/app/v1/news/weekly-capabilities', expected=401, authenticated=False, port=23200)
    capability, _ = get('/internal/v1/news/weekly-capabilities')
    assert capability['manualWeeklyEnabled'] and not capability['automaticUpdates'], 'MODE_INVALID'
    history, _ = get('/internal/v1/news/weekly-reports?limit=100', owner)
    assert sum(item['id'] == job_id for item in history['items']) == 1, 'HISTORY_DUPLICATED'
    return {'jobId': job_id, 'status': report['status'], 'attempt': job['attempt'], 'revision': report['revision'],
            'articleCount': len(events), 'uniqueImageCount': len(assets), 'categories': stats,
            'failedSections': report['failedSections'], 'ownerIsolation': True, 'anonymousDenied': True,
            'historyEntriesForJob': 1, 'automaticUpdates': False,
            'verifiedAt': datetime.now(timezone.utc).isoformat()}, 0 if not report['failedSections'] else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('job_id')
    arguments = parser.parse_args()
    try:
        result, code = verify(arguments.job_id)
    except Exception as error:
        result, code = {'failed': True, 'type': type(error).__name__,
                        'code': str(error) if isinstance(error, AssertionError) else 'VERIFICATION_FAILED'}, 1
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(code)
