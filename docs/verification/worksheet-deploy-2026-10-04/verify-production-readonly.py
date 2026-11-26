#!/usr/bin/env python3
"""发布后静态资源与教材 API 只读验收；仅发起 GET，不创建会话或上报统计。"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import time
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    """重定向不是正确静态响应，不跟随到其他站点。"""
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def digest(value):
    """生成统一排序的 JSON 内容指纹。"""
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def expected_mimes(path):
    """按浏览器执行要求检查脚本、样式、字体与二进制资源类型。"""
    suffix = PurePosixPath(path).suffix.lower()
    choices = {
        '.html': {'text/html'}, '.js': {'text/javascript', 'application/javascript', 'application/x-javascript'},
        '.mjs': {'text/javascript', 'application/javascript', 'application/x-javascript'},
        '.css': {'text/css'}, '.wasm': {'application/wasm'}, '.json': {'application/json'},
        '.svg': {'image/svg+xml'}, '.gz': {'application/gzip', 'application/x-gzip', 'application/octet-stream'},
        '.otf': {'font/otf', 'application/font-sfnt', 'application/vnd.ms-opentype', 'application/x-font-opentype', 'application/octet-stream'},
        '.ttf': {'font/ttf', 'application/font-sfnt', 'application/x-font-ttf', 'application/octet-stream'},
        '.pfb': {'application/x-font-type1', 'font/type1', 'application/octet-stream'},
        '.bcmap': {'application/octet-stream'}, '.txt': {'text/plain'},
        '': {'text/plain', 'application/octet-stream'}
    }
    if suffix not in choices:
        raise ValueError(f'Unspecified MIME policy for manifest path: {path}')
    return sorted(choices[suffix])


def read_plan(manifest_path, content_root):
    """仅从当前发布清单及本地教材构造预期值，不写入远端。"""
    manifest = json.loads(manifest_path.read_text())
    seen = set()
    for item in manifest['files']:
        path = PurePosixPath(item['path'])
        if path.is_absolute() or '..' in path.parts or item['path'] in seen:
            raise ValueError('Invalid or duplicate release path')
        if not re.fullmatch('[a-f0-9]{64}', item['sha256']) or not isinstance(item['bytes'], int) or item['bytes'] < 0:
            raise ValueError('Invalid release hash or size')
        seen.add(item['path'])
        expected_mimes(item['path'])
    if 'index.html' not in seen:
        raise ValueError('Release manifest has no index.html')
    published = json.loads((content_root / 'catalog/published.json').read_text())
    requirements = json.loads((content_root / 'catalog/requirements.json').read_text())
    books = []
    for edition in published['editions']:
        location = content_root / 'textbooks' / edition['editionId'] / edition['contentVersion'] / 'book.json'
        book = json.loads(location.read_text())
        if book['edition']['editionId'] != edition['editionId'] or book['edition']['contentVersion'] != edition['contentVersion']:
            raise ValueError('Local curriculum identity mismatch')
        books.append(book)
    coverage = []
    for slot in requirements['slots']:
        matched = [edition for edition in published['editions'] if all(edition[field] == slot[field] for field in ['subject', 'grade', 'semester'])]
        coverage.append({**{field: slot[field] for field in ['subject', 'grade', 'semester']},
                         'coverageStatus': 'missing' if not matched else 'complete' if any(edition['coverageStatus'] == 'complete' for edition in matched) else 'partial',
                         'editionIds': sorted(edition['editionId'] for edition in matched)})
    return manifest, published, books, coverage


def fetch(base_url, path, maximum, timeout, include_body=False):
    """不使用 CookieJar，不添加 Authorization，不保存响应 Cookie 或其他敏感头。"""
    record = {'path': path, 'status': None, 'mime': None, 'bytes': 0, 'sha256': None, 'durationMs': None, 'error': None}
    started = time.monotonic()
    chunks = []
    hasher = hashlib.sha256()
    try:
        request = Request(base_url + path, method='GET', headers={
            'User-Agent': 'Yuyutian-Release-ReadOnly-Check/1.0', 'Accept-Encoding': 'identity', 'Cache-Control': 'no-cache'
        })
        with build_opener(NoRedirect()).open(request, timeout=timeout) as response:
            record['status'] = response.status
            record['mime'] = response.headers.get_content_type().lower()
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                record['bytes'] += len(chunk)
                if record['bytes'] > maximum:
                    raise ValueError('Response exceeds planned size bound')
                hasher.update(chunk)
                if include_body:
                    chunks.append(chunk)
            record['sha256'] = hasher.hexdigest()
    except HTTPError as error:
        record['status'] = error.code
        record['mime'] = error.headers.get_content_type().lower() if error.headers else None
        record['error'] = f'HTTP_{error.code}'
    except (URLError, TimeoutError, OSError, ValueError) as error:
        record['error'] = type(error).__name__
    record['durationMs'] = round((time.monotonic() - started) * 1000)
    return record, b''.join(chunks)


def verify_static(base_url, item, timeout):
    path = '/' + quote(item['path'], safe='/')
    response, _ = fetch(base_url, path, max(item['bytes'] + 1024, 1024 * 1024), timeout)
    checks = {'status': response['status'] == 200, 'mime': response['mime'] in expected_mimes(item['path']),
              'bytes': response['bytes'] == item['bytes'], 'sha256': response['sha256'] == item['sha256']}
    return {**response, 'expectedBytes': item['bytes'], 'expectedSha256': item['sha256'],
            'acceptedMimes': expected_mimes(item['path']), 'checks': checks, 'passed': all(checks.values())}


def json_api(base_url, path, requests, timeout):
    """响应正文只用于解析，报告只保留公共教材核对值及安全请求摘要。"""
    response, body = fetch(base_url, path, 10 * 1024 * 1024, timeout, include_body=True)
    response['passed'] = False
    requests.append(response)
    if response['status'] != 200 or response['error'] or response['mime'] != 'application/json':
        raise ValueError(f'API response unavailable: {path}')
    try:
        envelope = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ValueError(f'API returned invalid JSON: {path}') from None
    response['envelopeOk'] = envelope.get('code') == 'OK'
    response['passed'] = response['envelopeOk'] and isinstance(envelope.get('data'), dict)
    if not response['passed']:
        raise ValueError(f'API envelope rejected: {path}')
    return envelope['data']


def normalized_coverage(items):
    rows = [{**{field: item[field] for field in ['subject', 'grade', 'semester', 'coverageStatus']},
             'editionIds': sorted(item['editionIds'])} for item in items]
    return sorted(rows, key=lambda item: (item['subject'], item['grade'], item['semester']))


def verify_curriculum(base_url, published, books, coverage, timeout):
    requests, results = [], []
    catalog_result = {'passed': False, 'checks': {}, 'error': None}
    try:
        catalog = json_api(base_url, '/public/v1/textbooks', requests, timeout)
        expected_ids = sorted((item['editionId'], item['contentVersion']) for item in published['editions'])
        actual_ids = sorted((item['editionId'], item['contentVersion']) for item in catalog['editions'])
        checked_fields = ['subject', 'grade', 'semester', 'reviewStatus', 'coverageStatus', 'availableEntryCount', 'contentSha256']
        expected_editions = {(item['editionId'], item['contentVersion']): {field: item.get(field) for field in checked_fields} for item in published['editions']}
        actual_editions = {(item['editionId'], item['contentVersion']): {field: item.get(field) for field in checked_fields} for item in catalog['editions']}
        checks = {'publishedVersions': actual_ids == expected_ids, 'editionFields': actual_editions == expected_editions,
                  'coverage': normalized_coverage(catalog['coverage']) == normalized_coverage(coverage)}
        catalog_result.update({'checks': checks, 'passed': all(checks.values()), 'editionCount': len(actual_ids),
                               'expectedEditionCount': len(expected_ids), 'coverage': normalized_coverage(catalog['coverage']),
                               'coverageSlotCount': len(catalog['coverage'])})
    except (ValueError, KeyError, TypeError) as error:
        catalog_result['error'] = str(error)
    # 教材分页顺序执行，静态检查已结束；全程网络并发不超过配置的静态工作线程数。
    for book in books:
        edition = book['edition']
        prefix = '/public/v1/textbooks/' + quote(edition['editionId'], safe='') + '/versions/' + quote(edition['contentVersion'], safe='')
        result = {'editionId': edition['editionId'], 'contentVersion': edition['contentVersion'], 'expectedEntries': len(book['entries']),
                  'passed': False, 'checks': {}, 'pagination': [], 'error': None}
        try:
            detail = json_api(base_url, prefix, requests, timeout)
            unit_fields, lesson_fields = ['unitId', 'editionId', 'order', 'title'], ['lessonId', 'unitId', 'order', 'title']
            def rows(items, fields, identity):
                return sorted(({field: item.get(field) for field in fields} for item in items), key=lambda item: item[identity])
            result['checks'].update({
                'editionIdentity': detail['edition']['editionId'] == edition['editionId'] and detail['edition']['contentVersion'] == edition['contentVersion'],
                'unitNamesAndIdentity': rows(detail['units'], unit_fields, 'unitId') == rows(book['units'], unit_fields, 'unitId'),
                'lessonNamesAndIdentity': rows(detail['lessons'], lesson_fields, 'lessonId') == rows(book['lessons'], lesson_fields, 'lessonId'),
                'declaredEntryCount': detail['entryCount'] == len(book['entries'])
            })
            result['unitNames'] = [item['title'] for item in detail['units']]
            result['lessonNames'] = [item['title'] for item in detail['lessons']]
            entries, cursor, seen_cursors = [], None, set()
            max_pages = len(book['entries']) // 200 + 3
            for page_number in range(1, max_pages + 1):
                parameters = {'limit': 200}
                if cursor is not None:
                    parameters['cursor'] = cursor
                page = json_api(base_url, prefix + '/entries?' + urlencode(parameters), requests, timeout)
                result['pagination'].append({'page': page_number, 'returned': len(page['entries']), 'total': page['total'],
                                             'version': page['contentVersion'], 'hasNext': bool(page['nextCursor'])})
                if page['contentVersion'] != edition['contentVersion'] or page['total'] != len(book['entries']):
                    raise ValueError('Entry page version or total differs from local published book')
                entries.extend(page['entries'])
                cursor = page['nextCursor']
                if not cursor:
                    break
                if cursor in seen_cursors:
                    raise ValueError('Repeated pagination cursor')
                seen_cursors.add(cursor)
            if cursor:
                raise ValueError('Pagination did not terminate within expected bounds')
            fields = ['entryId', 'entryVersion', 'editionId', 'unitId', 'lessonId', 'order', 'form', 'listTypes', 'text',
                      'pinyinSegments', 'meaning', 'senseId', 'reviewStatus', 'blockingIssues']
            expected_rows = rows(book['entries'], fields, 'entryId')
            actual_rows = rows(entries, fields, 'entryId')
            result.update({'actualEntries': len(entries), 'entryContentSha256': digest(actual_rows), 'expectedEntryContentSha256': digest(expected_rows),
                           'reviewStates': sorted(set(item['reviewStatus'] for item in entries))})
            result['checks'].update({'allEntriesRead': len(entries) == len(book['entries']),
                                     'noDuplicateIds': len({item['entryId'] for item in entries}) == len(entries),
                                     'entryContentAndOrder': actual_rows == expected_rows,
                                     'reviewedOrPublishedOnly': all(item['reviewStatus'] in ['reviewed', 'published'] and not item['blockingIssues'] for item in entries)})
            result['passed'] = all(result['checks'].values())
        except (ValueError, KeyError, TypeError) as error:
            result['error'] = str(error)
        results.append(result)
    return {'passed': catalog_result['passed'] and all(item['passed'] for item in results), 'catalog': catalog_result, 'books': results, 'requests': requests}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    here = Path(__file__).resolve().parent
    repo = here.parents[2]
    parser.add_argument('--base-url', required=True, help='Explicit HTTPS origin, without credentials or query')
    parser.add_argument('--manifest', type=Path, default=here / 'release-files.json')
    parser.add_argument('--content-root', type=Path, default=repo / 'toolbox-web/content')
    parser.add_argument('--output', type=Path, default=here / ('http-readonly-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
    parser.add_argument('--workers', type=int, choices=range(1, 5), default=3)
    parser.add_argument('--timeout', type=int, choices=range(5, 121), default=45)
    parser.add_argument('--prepare-only', action='store_true', help='Validate local plan without any network request')
    args = parser.parse_args()
    origin = urlparse(args.base_url)
    if origin.scheme != 'https' or not origin.hostname or origin.username or origin.password or origin.path not in ['', '/'] or origin.query or origin.fragment:
        parser.error('--base-url must be an explicit HTTPS origin without credentials, path, query or fragment')
    base_url = args.base_url.rstrip('/')
    manifest, published, books, coverage = read_plan(args.manifest, args.content_root)
    plan = {'releaseId': manifest['releaseId'], 'baseUrl': base_url, 'staticFiles': len(manifest['files']),
            'staticBytes': sum(item['bytes'] for item in manifest['files']), 'publishedBooks': len(books),
            'entryCount': sum(len(book['entries']) for book in books), 'coverageSlots': len(coverage),
            'maxConcurrentRequests': args.workers, 'methods': ['GET'], 'credentials': 'none',
            'manifestSha256': hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
            'expectedCatalogSha256': digest(published), 'prepareOnly': args.prepare_only}
    if args.prepare_only:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0
    # 显式执行才创建证据目录；拒绝覆盖上次报告。
    args.output.mkdir(parents=True, exist_ok=False)
    started = timestamp()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        static = list(pool.map(lambda item: verify_static(base_url, item, args.timeout), manifest['files']))
    # 另核对根路径是否提供同一个 index 文件，不能只检查 /index.html。
    index = next(item for item in manifest['files'] if item['path'] == 'index.html')
    root_response, _ = fetch(base_url, '/', max(index['bytes'] + 1024, 1024 * 1024), args.timeout)
    root_response['checks'] = {'status': root_response['status'] == 200, 'mime': root_response['mime'] == 'text/html',
                               'bytes': root_response['bytes'] == index['bytes'], 'sha256': root_response['sha256'] == index['sha256']}
    root_response['passed'] = all(root_response['checks'].values())
    api = verify_curriculum(base_url, published, books, coverage, args.timeout)
    passed = all(item['passed'] for item in static) and root_response['passed'] and api['passed']
    summary = {'releaseId': manifest['releaseId'], 'startedAt': started, 'finishedAt': timestamp(), 'passed': passed,
               'static': {'total': len(static), 'passed': sum(item['passed'] for item in static), 'failed': sum(not item['passed'] for item in static)},
               'rootIndexPassed': root_response['passed'], 'curriculumPassed': api['passed'],
               'curriculumBooks': len(api['books']), 'curriculumEntries': sum(item.get('actualEntries', 0) for item in api['books']),
               'requestCount': len(static) + 1 + len(api['requests']), 'report': str(args.output / 'report.json'),
               'boundary': 'Read-only HTTP resource and public curriculum checks; not browser interaction or PDF button download evidence'}
    (args.output / 'report.json').write_text(json.dumps({'summary': summary, 'plan': plan, 'rootIndex': root_response,
                                                      'static': static, 'curriculum': api}, ensure_ascii=False, indent=2) + '\n')
    (args.output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
