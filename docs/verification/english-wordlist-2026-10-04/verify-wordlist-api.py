#!/usr/bin/env python3
"""只读核对英语 1.0.1 词表；--self-test/--plan 均不访问网络或写验收报告。"""
import argparse
import copy
from email.message import Message
import hashlib
from http.client import HTTPException
import io
import json
import math
from pathlib import Path
import socket
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


USER_AGENT = 'Yuyutian-Release-ReadOnly-Check/1.0'
EDITION = 'en-yl-3-1-yilin-2024'
VERSION = '1.0.1'
UNIT_COUNTS = [13, 11, 17, 9, 18, 12, 31, 18, 11]
LIMIT = 37
MAX_BODY = 8 * 1024 * 1024
RETRY_STATUSES = {408, 425, 429, 500, 502, 503, 504}
# 上轮已发布的四册语文身份锚点，避免同时修改本地清单后误称“语文未变”。
CHINESE_BASELINE = {
    'cn-ty-1-1-pep-2024': ('1.0.2', '20017f83d008c9942ff1bf52dc962bda43db49165ff8881cdd8500a753844527', 318),
    'cn-ty-2-1-pep-2017': ('1.0.2', '1d14e1fd6d3c819dfa5a74b00de26d9476e04e7f9d5e5780a97dff71914f8778', 760),
    'cn-ty-3-1-pep-2024': ('1.0.0', '49abe27d8b49e50b99af599726d0374b13071b47f4447538fcaab65b74fd7cdf', 777),
    'cn-ty-4-1-pep-2024': ('1.0.0', 'ac988e6772a39464921fae77581faa484e407cad10ceffa88cc5a908d720d295', 640),
}


def sha256(value):
    """计算公开文件或结构摘要，不在报告中复制响应正文。"""
    if not isinstance(value, bytes):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(value).hexdigest()


def differences(expected, actual, path='$'):
    """返回完整递归差异位置；不输出字段值、请求头或非教材响应正文。"""
    if type(expected) is not type(actual):
        return [{'path': path, 'kind': 'type', 'expectedType': type(expected).__name__, 'actualType': type(actual).__name__}]
    if isinstance(expected, dict):
        result = []
        for key in sorted(expected.keys() | actual.keys()):
            location = path + '/' + str(key).replace('~', '~0').replace('/', '~1')
            if key not in actual:
                result.append({'path': location, 'kind': 'missing'})
            elif key not in expected:
                result.append({'path': location, 'kind': 'unexpected'})
            else:
                result.extend(differences(expected[key], actual[key], location))
        return result
    if isinstance(expected, list):
        result = [] if len(expected) == len(actual) else [{'path': path, 'kind': 'length', 'expectedCount': len(expected), 'actualCount': len(actual)}]
        for index, (left, right) in enumerate(zip(expected, actual)):
            result.extend(differences(left, right, f'{path}/{index}'))
        return result
    return [] if expected == actual else [{'path': path, 'kind': 'value'}]


def require(condition, code):
    """身份或契约不符即失败，不用重试掩盖不一致。"""
    if not condition:
        raise ValueError(code)


def load_expected(catalog_path, book_path):
    """从发布清单和完整正文建立期望，只有 edition 的正文哈希由清单补入。"""
    catalog_bytes, book_bytes = catalog_path.read_bytes(), book_path.read_bytes()
    published, book = json.loads(catalog_bytes), json.loads(book_bytes)
    editions = sorted(published['editions'], key=lambda item: (item['subject'], item['grade'], item['semester'], item['editionId'], item['contentVersion']))
    require(len(editions) == 5 and len({item['editionId'] for item in editions}) == 5, 'LOCAL_CATALOG_IDENTITIES')
    english = next(item for item in editions if item['editionId'] == EDITION)
    require(english['subject'] == 'english' and english['contentVersion'] == VERSION, 'LOCAL_ENGLISH_VERSION')
    require(sha256(book_bytes) == english['contentSha256'], 'LOCAL_BOOK_HASH')
    expected_edition = {**book['edition'], 'contentSha256': english['contentSha256']}
    require(not differences(english, expected_edition), 'LOCAL_EDITION_FIELDS')
    actual_chinese = {item['editionId']: (item['contentVersion'], item['contentSha256'], item['availableEntryCount'])
                      for item in editions if item['subject'] == 'chinese'}
    require(actual_chinese == CHINESE_BASELINE, 'LOCAL_CHINESE_BASELINE_CHANGED')
    require(len(book['units']) == 9 and book['lessons'] == [], 'LOCAL_STRUCTURE_COUNTS')
    require([unit['order'] for unit in book['units']] == list(range(1, 10)), 'LOCAL_UNIT_ORDER')
    order = {unit['unitId']: unit['order'] for unit in book['units']}
    entries = sorted(book['entries'], key=lambda item: (order[item['unitId']], item['order'], item['entryId']))
    require(len(entries) == english['availableEntryCount'] == english['expectedEntryCount'] == 140, 'LOCAL_ENTRY_COUNT')
    require(len({item['entryId'] for item in entries}) == 140, 'LOCAL_DUPLICATE_ENTRY_ID')
    require(all(item['editionId'] == EDITION and item['entryVersion'] == VERSION and item['lessonId'] is None
                and item['listTypes'] == ['vocabulary'] for item in entries), 'LOCAL_ENTRY_IDENTITY')
    counts = [sum(item['unitId'] == unit['unitId'] for item in entries) for unit in book['units']]
    require(counts == UNIT_COUNTS, 'LOCAL_UNIT_COUNTS')
    coverage = []
    # API 契约固定为语文 1–6 年级、英语 3–6 年级的上下册共 20 槽。
    for subject, grades in [('chinese', range(1, 7)), ('english', range(3, 7))]:
        for grade in grades:
            for semester in ['first', 'second']:
                found = [item for item in editions if (item['subject'], item['grade'], item['semester']) == (subject, grade, semester)]
                status = 'missing' if not found else 'complete' if any(item['coverageStatus'] == 'complete' for item in found) else 'partial'
                coverage.append({'subject': subject, 'grade': grade, 'semester': semester,
                                 'coverageStatus': status, 'editionIds': [item['editionId'] for item in found]})
    return {
        'catalog': {'catalogVersion': published['catalogVersion'], 'editions': editions, 'coverage': coverage},
        'version': {'edition': expected_edition, 'units': book['units'], 'lessons': [], 'listTypes': ['vocabulary'],
                    'entryCount': 140, 'counts': {'units': 9, 'lessons': 0, 'entries': 140}, 'sha256': english['contentSha256']},
        'entries': entries,
        'localFiles': {'catalogSha256': sha256(catalog_bytes), 'bookSha256': sha256(book_bytes)},
    }


class NoRedirect(HTTPRedirectHandler):
    """验收明确的同域地址；重定向需单独调查，不自动请求其他站点。"""
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class ApiClient:
    """顺序 GET，最多三次短时重试；完整保留每次失败和恢复记录。"""
    def __init__(self, base, timeout=20, opener=None, sleeper=time.sleep):
        parsed = urlsplit(base)
        require(parsed.scheme in ('http', 'https') and parsed.hostname and not parsed.username and not parsed.password
                and not parsed.query and not parsed.fragment and parsed.path in ('', '/'), 'INVALID_BASE_ORIGIN')
        self.base, self.timeout = base.rstrip('/'), timeout
        self.open = opener or build_opener(NoRedirect()).open
        self.sleep = sleeper
        self.requests = []

    def get(self, path):
        record = {'path': path, 'method': 'GET', 'passed': False, 'attempts': []}
        self.requests.append(record)
        for attempt in range(1, 4):
            started = time.monotonic()
            detail = {'attempt': attempt, 'status': None, 'mime': None, 'bytes': 0, 'sha256': None,
                      'error': None, 'retryable': False}
            record['attempts'].append(detail)
            try:
                request = Request(self.base + path, headers={'User-Agent': USER_AGENT, 'Accept': 'application/json', 'Cache-Control': 'no-cache'}, method='GET')
                with self.open(request, timeout=self.timeout) as response:
                    detail['status'] = response.status
                    detail['mime'] = response.headers.get_content_type()
                    require(response.status == 200, 'HTTP_STATUS')
                    require(detail['mime'] == 'application/json', 'RESPONSE_MIME')
                    body = response.read(MAX_BODY + 1)
                    detail['bytes'], detail['sha256'] = len(body), sha256(body)
                    require(len(body) <= MAX_BODY, 'RESPONSE_TOO_LARGE')
                    envelope = json.loads(body)
                    require(isinstance(envelope, dict) and envelope.get('code') == 'OK' and isinstance(envelope.get('data'), dict), 'API_ENVELOPE')
                    record['passed'] = True
                    return envelope['data']
            except HTTPError as error:
                detail['status'] = error.code
                detail['error'] = 'HTTP_ERROR'
                detail['retryable'] = error.code in RETRY_STATUSES
                error.close()
            except (URLError, TimeoutError, socket.timeout, ConnectionError, HTTPException):
                detail['error'], detail['retryable'] = 'TRANSPORT_ERROR', True
            except (ValueError, UnicodeDecodeError):
                detail['error'] = 'INVALID_JSON_OR_RESPONSE_CONTRACT'
            finally:
                detail['durationMs'] = round((time.monotonic() - started) * 1000)
            if not detail['retryable'] or attempt == 3:
                raise ValueError(detail['error'])
            self.sleep(attempt)
        raise ValueError('REQUEST_NOT_COMPLETED')


def comparison(name, expected, actual):
    """深度对比所有字段与顺序，不删去来源、义项等低可见度字段。"""
    mismatches = differences(expected, actual)
    return {'name': name, 'passed': not mismatches, 'differences': mismatches,
            'expectedSha256': sha256(expected), 'actualSha256': sha256(actual)}


def verify_pages(client, prefix, expected, unit_id=None):
    """每页重建相同过滤条件，校验版本、总量、游标终止与完整条目。"""
    result = {'name': f'unit:{unit_id}' if unit_id else 'all-vocabulary', 'passed': False,
              'expectedCount': len(expected), 'actualCount': 0, 'pages': [], 'differences': []}
    collected, cursor, seen = [], None, set()
    try:
        for number in range(1, math.ceil(len(expected) / LIMIT) + 2):
            params = {'limit': LIMIT, 'listTypes': 'vocabulary'}
            if unit_id is not None:
                params['unitIds'] = unit_id
            if cursor is not None:
                params['cursor'] = cursor
            page = client.get(prefix + '/entries?' + urlencode(params))
            require(set(page) == {'entries', 'nextCursor', 'total', 'contentVersion'}, 'PAGE_FIELDS')
            require(page['contentVersion'] == VERSION, 'PAGE_VERSION_IDENTITY')
            require(type(page['total']) is int and page['total'] == len(expected), 'PAGE_TOTAL_IDENTITY')
            require(isinstance(page['entries'], list), 'PAGE_ENTRY_TYPE')
            page_record = {'page': number, 'returned': len(page['entries']), 'total': page['total'],
                           'contentVersion': page['contentVersion'], 'filters': {'listTypes': 'vocabulary', 'unitIds': unit_id},
                           'hasNext': page['nextCursor'] is not None}
            result['pages'].append(page_record)
            expected_page = expected[len(collected):len(collected) + LIMIT]
            result['differences'].extend(differences(expected_page, page['entries'], f'$/pages/{number}/entries'))
            collected.extend(page['entries'])
            require(len(page['entries']) <= LIMIT and len(collected) <= len(expected), 'PAGE_SIZE')
            cursor = page['nextCursor']
            if cursor is None:
                break
            require(isinstance(cursor, str) and bool(cursor) and cursor not in seen and bool(page['entries']), 'CURSOR_INVALID_OR_REPEATED')
            seen.add(cursor)
        require(cursor is None, 'PAGINATION_NOT_TERMINATED')
        require(len(collected) == len(expected), 'PAGINATION_INCOMPLETE')
        require(len({item['entryId'] for item in collected}) == len(collected), 'DUPLICATE_ENTRY_ID')
        result['differences'].extend(differences(expected, collected, '$/entries'))
        result['passed'] = not result['differences']
    except (ValueError, KeyError, TypeError) as error:
        # 固定错误码或异常类别，不把服务器正文与环境信息写入报告。
        result['error'] = str(error) if isinstance(error, ValueError) else type(error).__name__
    result['actualCount'] = len(collected)
    result['expectedSha256'], result['actualSha256'] = sha256(expected), sha256(collected)
    return result


def verify(client, expected):
    """目录、英语结构、全量分页与九个单元分别给出独立结果。"""
    prefix = '/public/v1/textbooks/' + quote(EDITION, safe='') + '/versions/' + VERSION
    checks = []
    for name, path, target in [('catalog-all-five-and-coverage', '/public/v1/textbooks', expected['catalog']),
                               ('english-version', prefix, expected['version'])]:
        try:
            checks.append(comparison(name, target, client.get(path)))
        except (ValueError, KeyError, TypeError) as error:
            checks.append({'name': name, 'passed': False, 'error': str(error) if isinstance(error, ValueError) else type(error).__name__})
    checks.append(verify_pages(client, prefix, expected['entries']))
    for unit in expected['version']['units']:
        rows = [item for item in expected['entries'] if item['unitId'] == unit['unitId']]
        checks.append(verify_pages(client, prefix, rows, unit['unitId']))
    return {'passed': all(item['passed'] for item in checks), 'checks': checks, 'requests': client.requests,
            'summary': {'checks': len(checks), 'passedChecks': sum(item['passed'] for item in checks),
                        'requests': len(client.requests), 'attempts': sum(len(item['attempts']) for item in client.requests),
                        'expectedEnglishEntries': 140, 'expectedUnitCounts': UNIT_COUNTS,
                        'chineseBaselineAnchors': 4}}


def offline_self_test(expected):
    """用内存响应验证门禁与分页参数，不把模拟结果当成线上验收。"""
    class FakeClient:
        def __init__(self, corrupt=False, wrong_version=False):
            self.requests, self.corrupt, self.wrong_version = [], corrupt, wrong_version

        def get(self, path):
            self.requests.append({'path': path, 'attempts': []})
            parsed = urlsplit(path)
            if parsed.path == '/public/v1/textbooks':
                return copy.deepcopy(expected['catalog'])
            if not parsed.path.endswith('/entries'):
                return copy.deepcopy(expected['version'])
            query = parse_qs(parsed.query)
            require(query['listTypes'] == ['vocabulary'] and query['limit'] == ['37'], 'SELF_TEST_FILTER_LOST')
            rows = [item for item in expected['entries'] if 'unitIds' not in query or item['unitId'] == query['unitIds'][0]]
            offset = int(query.get('cursor', ['0'])[0])
            page = copy.deepcopy(rows[offset:offset + LIMIT])
            if self.corrupt and offset == 0:
                page[0]['sourceRefs'][0]['locator'] += ' altered'
            return {'entries': page, 'total': len(rows), 'contentVersion': '1.0.0' if self.wrong_version else VERSION,
                    'nextCursor': str(offset + LIMIT) if offset + LIMIT < len(rows) else None}

    good = verify(FakeClient(), expected)
    require(good['passed'] and good['summary']['requests'] == 15, 'SELF_TEST_POSITIVE')
    bad = verify(FakeClient(corrupt=True), expected)
    require(not bad['passed'] and any('/sourceRefs/' in diff['path'] for item in bad['checks'] for diff in item.get('differences', [])), 'SELF_TEST_DEEP_FIELD')
    wrong = verify(FakeClient(wrong_version=True), expected)
    require(not wrong['passed'] and wrong['checks'][2]['error'] == 'PAGE_VERSION_IDENTITY', 'SELF_TEST_IDENTITY_GATE')
    require(differences({'v': 1}, {'v': True}) and differences({'v': None}, {}), 'SELF_TEST_STRICT_TYPES')

    class FakeResponse:
        status = 200
        headers = Message()
        headers['Content-Type'] = 'application/json'

        def __enter__(self):
            return self

        def __exit__(self, *unused):
            return False

        def read(self, unused):
            return b'{"code":"OK","data":{}}'

    attempts, delays = [], []

    def recovering_opener(request, timeout):
        require(request.get_method() == 'GET' and request.get_header('User-agent') == USER_AGENT, 'SELF_TEST_REQUEST_HEADERS')
        attempts.append(timeout)
        if len(attempts) == 1:
            raise URLError('simulated transient failure')
        if len(attempts) == 2:
            raise HTTPError(request.full_url, 503, 'simulated', Message(), io.BytesIO())
        return FakeResponse()

    client = ApiClient('https://example.invalid', opener=recovering_opener, sleeper=delays.append)
    require(client.get('/public/v1/textbooks') == {} and len(attempts) == 3 and delays == [1, 2], 'SELF_TEST_RETRY_RECOVERY')
    require([item['status'] for item in client.requests[0]['attempts']] == [None, 503, 200], 'SELF_TEST_FIRST_FAILURE_PRESERVED')

    def missing_opener(request, timeout):
        raise HTTPError(request.full_url, 404, 'simulated', Message(), io.BytesIO())

    client = ApiClient('https://example.invalid', opener=missing_opener, sleeper=delays.append)
    try:
        client.get('/public/v1/textbooks')
        raise AssertionError('SELF_TEST_MISSING_IDENTITY_ACCEPTED')
    except ValueError:
        require(len(client.requests[0]['attempts']) == 1 and not client.requests[0]['passed'], 'SELF_TEST_404_MUST_NOT_RETRY')
    return {'selfTest': 'pass', 'networkRequests': 0, 'checks': ['full-pagination', 'filter-retention', 'deep-source-field', 'version-identity-failure', 'strict-types', 'transient-retry-recovery', 'first-failure-preserved', '404-not-retried'],
            'note': 'Offline simulated checks only; production acceptance has not run.'}


def main():
    here = Path(__file__).resolve().parent
    repository = here.parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='https://toolbox.yuyutian.top')
    parser.add_argument('--catalog', type=Path, default=repository / 'toolbox-web/content/catalog/published.json')
    parser.add_argument('--book', type=Path, default=repository / f'toolbox-web/content/textbooks/{EDITION}/{VERSION}/book.json')
    parser.add_argument('--output', type=Path, default=here / 'wordlist-api.json')
    parser.add_argument('--timeout', type=float, default=20)
    parser.add_argument('--plan', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    require(0 < args.timeout <= 60, 'INVALID_TIMEOUT')
    expected = load_expected(args.catalog, args.book)
    if args.self_test:
        print(json.dumps(offline_self_test(expected), ensure_ascii=False))
        return 0
    if args.plan:
        print(json.dumps({'networkRequests': 0, 'plannedRequests': 15, 'concurrency': 1, 'englishVersion': VERSION,
                          'unitCounts': UNIT_COUNTS, 'localFiles': expected['localFiles']}, ensure_ascii=False))
        return 0
    # 旧原始证据不能被本轮覆盖；重跑时需明确指定新的 --output。
    require(not args.output.exists(), 'OUTPUT_ALREADY_EXISTS_USE_NEW_PATH')
    client = ApiClient(args.base, args.timeout)
    result = verify(client, expected)
    result.update({'checkedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'base': client.base,
                   'userAgent': USER_AGENT, 'localFiles': expected['localFiles'], 'readOnly': True,
                   'scope': 'Public textbook API only; no browser, deployment or OCR accuracy claim.'})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
        handle.write('\n')
    print(json.dumps({'passed': result['passed'], **result['summary'], 'report': str(args.output)}, ensure_ascii=False))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
