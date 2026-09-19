#!/usr/bin/env python3
"""Bounded real-shelf acceptance, after explicit user authorization; never print secrets."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request

BASE = Path('/opt/yuyutian/mytools/runtime/adaptation-production-20260911')
OWNER = 2054944045960138752
SHELF = '6245915b-a9e5-4142-89f9-96d94024829d'
DIRECTORY = BASE / 'operator/real-shelf-acceptance'
ROOT = '/api/v1/reader-state'
SHELF_PATH = '/shelves/' + SHELF
KEY = 'production-real-chapter-20260911'
INTENTS = ['\u53ea\u5728\u5f00\u5934\u57ce\u5e02\u4e0e\u82cf\u5bb6\u5927\u5b85\u7684\u73af\u5883\u63cf\u5199\u5904\u589e\u8865\u4e24\u4e09\u53e5\uff0c\u517140\u81f380\u5b57\uff0c\u589e\u5f3a\u7a7a\u95f4\u611f\u548c\u5b89\u9759\u7684\u6c1b\u56f4\u3002\u9010\u5b57\u4fdd\u7559\u539f\u6587\uff0c\u4e0d\u589e\u52a0\u89d2\u8272\u3001\u4e8b\u4ef6\u3001\u8bbe\u5b9a\u6216\u4eba\u7269\u5df2\u77e5\u4fe1\u606f\uff1b\u4e0d\u6269\u5199\u4e24\u6027\u3001\u9a9a\u6270\u6216\u66b4\u529b\u5185\u5bb9\uff0c\u4e0d\u63d0\u524d\u7b2c\u4e8c\u7ae0\u7684\u63a5\u8f66\u3001\u89c1\u9762\u6216\u6bd4\u6b66\uff0c\u7ed3\u5c3e\u4ecd\u505c\u5728\u53f6\u67ab\u6253\u7b97\u6253\u7535\u8bdd\u3002',
    '\u4ec5\u7cbe\u70bc\u4e0a\u4e00\u7248\u65b0\u589e\u7684\u5f00\u5934\u73af\u5883\u63cf\u5199\uff0c\u4f7f\u63aa\u8f9e\u66f4\u81ea\u7136\u514b\u5236\uff0c\u907f\u514d\u91cd\u590d\uff0c\u65b0\u589e\u603b\u91cf\u4fdd\u630140\u81f380\u5b57\u3002\u539f\u6587\u9010\u5b57\u4fdd\u7559\uff0c\u4eba\u7269\u5173\u7cfb\u3001\u4fe1\u606f\u3001\u4e8b\u4ef6\u53ca\u7ed3\u5c3e\u4e0d\u53d8\uff0c\u4e0d\u6269\u5199\u4e24\u6027\u3001\u9a9a\u6270\u6216\u66b4\u529b\u5185\u5bb9\uff0c\u4e0d\u63d0\u524d\u4e0b\u4e00\u7ae0\u4e8b\u4ef6\u3002',
    '\u4ece\u539f\u7ae0\u91cd\u65b0\u589e\u8865\u5f00\u5934\u57ce\u5e02\u4e0e\u5b85\u9662\u7684\u58f0\u97f3\u6216\u5149\u7ebf\u7ec6\u8282\uff0c\u65b0\u589e\u4e24\u4e09\u53e5\uff0c\u517140\u81f380\u5b57\uff0c\u91c7\u7528\u4e0e\u4e0a\u4e00\u7248\u4e0d\u540c\u7684\u63aa\u8f9e\u3002\u9010\u5b57\u4fdd\u7559\u539f\u6587\uff0c\u4e0d\u589e\u52a0\u65b0\u4eba\u7269\u6216\u5267\u60c5\uff0c\u4e0d\u6269\u5199\u4e24\u6027\u3001\u9a9a\u6270\u6216\u66b4\u529b\uff0c\u4e0d\u63d0\u524d\u4e0b\u4e00\u7ae0\u4e8b\u4ef6\uff0c\u4fdd\u7559\u539f\u7ed3\u5c3e\u3002']
specification = importlib.util.spec_from_file_location('deployment', BASE / 'operator/deploy.py')
d = importlib.util.module_from_spec(specification)
specification.loader.exec_module(d)


def api(path, method='GET', body=None, expected=200):
    address = 'http://127.0.0.1:23230' + ROOT + path + ('&' if '?' in path else '?') + 'ownerId=' + str(OWNER)
    headers = {'Authorization': 'Bearer ' + d.environment()['READER_INTERNAL_TOKEN'], 'Content-Type': 'application/json'}
    request = urllib.request.Request(address, method=method, headers=headers,
        data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=90) as response:
            raw = response.read(2097153)
            assert len(raw) <= 2097152 and response.status == expected
            return json.loads(raw)
    except urllib.error.HTTPError as error:
        try:
            code = json.loads(error.read(4096)).get('code', 'UNKNOWN')
        except Exception:
            code = 'UNPARSEABLE'
        raise RuntimeError('reader_http_' + str(error.code) + ':' + code) from None


def save(name, value):
    d.write(DIRECTORY / (name + '.json'), json.dumps(value, ensure_ascii=True))


def emit(value):
    print(json.dumps(value, ensure_ascii=True), flush=True)


def prepare():
    features = api('/features/reader-adaptation')
    assert features['hasActiveConsent'] and features['disclosure']['version'] == 'production-20260911-v1'
    capability = api(SHELF_PATH + '/chapter-adaptation-capability/ensure', 'POST',
        {'idempotencyKey': KEY + '-prepare-v1'}, 202)
    deadline = time.monotonic() + 200
    previous = None
    while time.monotonic() < deadline:
        if capability != previous:
            emit(capability)
            previous = capability
        if capability['status'] != 'PREPARING':
            break
        time.sleep(3)
        capability = api(SHELF_PATH + '/chapter-adaptation-capability')
    save('preparation', capability)
    assert capability['status'] == 'READY', 'source_preparation_not_ready'
    catalog = api(SHELF_PATH + '/chapters?limit=30')
    save('catalog', catalog)
    emit({'catalog': catalog})


def preview(index):
    catalog = json.loads((DIRECTORY / 'catalog.json').read_text())
    selected = next(item for item in catalog['items'] if item['index'] == index)
    assert selected['eligible']
    entries = []
    for item in catalog['items']:
        if abs(item['index'] - index) <= 1:
            content = api(SHELF_PATH + '/chapters/' + item['chapterId'] + '/content')
            assert content['bindingRevision'] == catalog['bindingRevision'] and content['catalogRevision'] == catalog['catalogRevision']
            entries.append({'index': item['index'], 'title': item['title'], 'content': content})
    save('source-preview', {'selected': selected, 'catalog': catalog, 'entries': entries})
    emit({'selected': selected, 'entries': entries})


def fingerprint(value):
    return hashlib.sha256(value.encode()).hexdigest()


def progress(identifier):
    deadline = time.monotonic() + 600
    previous = None
    while time.monotonic() < deadline:
        result = api('/chapter-adaptations/' + identifier + '/status')
        state = (result['status'], result['currentStage'], result['candidateCount'], result['lastErrorCode'])
        if state != previous:
            emit(result)
            save(identifier + '-progress', result)
            previous = state
        if result['status'] in ('COMPLETED', 'FAILED', 'CANCELLED'):
            return result
        time.sleep(5)
    raise RuntimeError('adaptation_wait_deadline')


def source_proof(identifier):
    path = '/chapter-adaptations/' + identifier + '/source-check'
    result = api(path, 'POST', expected=202)
    deadline = time.monotonic() + 120
    while result['status'] != 'CURRENT' and time.monotonic() < deadline:
        assert result['status'] in ('QUEUED', 'CHECKING'), 'source_proof_not_current:' + result['status']
        time.sleep(2)
        result = api(path)
    assert result['status'] == 'CURRENT', 'source_proof_timeout'
    return result


def counters():
    with d.connection('READER') as db, db.cursor() as cursor:
        cursor.execute('SELECT COUNT(*),COALESCE(SUM(transmission_count),0) FROM novel_chapter_adaptation_attempt WHERE owner_id=%s', (OWNER,))
        attempts, transmissions = cursor.fetchone()
        return {'attempts': int(attempts), 'transmissions': int(transmissions)}


def batch():
    preview = json.loads((DIRECTORY / 'source-preview.json').read_text())
    selected = preview['selected']
    original = next(item['content'] for item in preview['entries'] if item['index'] == selected['index'])
    # 人工已阅读首章与下一章；验收只增补开头环境，不生成情色或暴力细节。
    assert selected['chapterId'] == '7f28daf5-7ddf-4314-80d8-47b389096045'
    assert original['sha256'] == '069fa1a6f13b199435c40355a03634c5ad0f0403ebbaf2815246401e0abcd2b2'
    assert fingerprint(original['text']) == original['sha256']
    assert api('/features/reader-adaptation')['hasActiveConsent']
    history_path = SHELF_PATH + '/chapters/' + selected['chapterId'] + '/adaptations'
    baseline_file = DIRECTORY / 'baseline.json'
    if not baseline_file.exists():
        shelves = api('/shelves')
        save('baseline', {'history': api(history_path), 'shelf': next(s for s in shelves if s['id'] == SHELF), 'calls': counters()})
    baseline = json.loads(baseline_file.read_text())
    completed = []
    for index, kind in enumerate(('INITIAL', 'OPTIMIZE', 'REGENERATE')):
        request_file = DIRECTORY / (kind + '-request.json')
        if request_file.exists():
            submitted = json.loads(request_file.read_text())
        else:
            proof = original if kind == 'INITIAL' else source_proof(completed[0]['adaptationId'])
            path = history_path if kind == 'INITIAL' else '/chapter-adaptations/' + completed[0]['adaptationId'] + '/' + kind.lower()
            submitted = {'path': path, 'body': {'idempotencyKey': KEY + '-' + kind.lower() + '-v1',
                'intent': INTENTS[index], 'expectedBindingRevision': proof['bindingRevision'],
                'expectedCatalogRevision': proof['catalogRevision'], 'expectedSourceSha256': proof['sha256'] if kind == 'INITIAL' else proof['sourceSha256']}}
            save(kind + '-request', submitted)
        receipt = api(submitted['path'], 'POST', submitted['body'], 202)
        save(kind + '-receipt', receipt)
        state = progress(receipt['adaptationId'])
        if state['status'] != 'COMPLETED':
            save('report', {'status': 'INCOMPLETE', 'failedKind': kind, 'progress': state, 'completed': completed, 'calls': counters()})
            raise RuntimeError('business_version_not_completed:' + kind)
        identifier = receipt['adaptationId']
        detail = api('/chapter-adaptations/' + identifier)
        comparison = api('/chapter-adaptations/' + identifier + '/comparison')
        save(kind + '-detail', detail)
        save(kind + '-comparison', comparison)
        output = detail['result']['content']
        assert fingerprint(output) == detail['result']['contentSha256'] == comparison['resultSha256']
        assert comparison['originalSha256'] == original['sha256']
        if comparison['mode'] == 'SIDE_BY_SIDE':
            reconstructed_original, reconstructed_output = comparison['original'], comparison['adapted']
        else:
            reconstructed_original = ''.join(value for hunk in comparison['hunks'] for value in hunk['original'])
            reconstructed_output = ''.join(value for hunk in comparison['hunks'] for value in (hunk['original'] if hunk['kind'] == 'EQUAL' else hunk['adapted']))
        assert reconstructed_original == original['text'] and reconstructed_output == output
        lineage = detail['version']['lineage']
        if kind != 'INITIAL':
            assert lineage['rootAdaptationId'] == lineage['triggerAdaptationId'] == completed[0]['adaptationId']
            assert lineage['parentAdaptationId'] == (completed[0]['adaptationId'] if kind == 'OPTIMIZE' else None)
        completed.append({'adaptationId': identifier, 'kind': kind, 'revision': receipt['revisionNumber'],
            'codepoints': len(output), 'sha256': detail['result']['contentSha256']})
    history = api(history_path)
    expected = {item['adaptationId'] for item in baseline['history']['items']} | {item['adaptationId'] for item in completed}
    assert expected == {item['adaptationId'] for item in history['items']}
    before_replay = counters()
    for item in completed:
        submitted = json.loads((DIRECTORY / (item['kind'] + '-request.json')).read_text())
        assert api(submitted['path'], 'POST', submitted['body'], 202)['adaptationId'] == item['adaptationId']
        assert api('/chapter-adaptations/' + item['adaptationId'])['result']['contentSha256'] == item['sha256']
    assert counters() == before_replay
    current = api(SHELF_PATH + '/chapters/' + selected['chapterId'] + '/content')
    assert current['text'] == original['text'] and current['sha256'] == original['sha256']
    assert next(s for s in api('/shelves') if s['id'] == SHELF) == baseline['shelf']
    report = {'status': 'REAL_SHELF_BACKEND_PASSED', 'completed': completed, 'originalUnchanged': True,
        'shelfUnchanged': True, 'historyPreserved': True, 'idempotencyNoNewModelCalls': True,
        'callsBefore': baseline['calls'], 'callsAfter': counters(), 'appPageAcceptance': 'NOT_PERFORMED',
        'qualityReview': 'PENDING_REVIEW_OF_ADOPTED_ADDITIONS'}
    save('report', report)
    emit(report)


if __name__ == '__main__':
    os.umask(0o077)
    try:
        assert os.geteuid() == 0
        if sys.argv[1] == 'prepare':
            prepare()
        elif sys.argv[1] == 'preview':
            preview(int(sys.argv[2]))
        elif sys.argv[1] == 'batch':
            batch()
        else:
            raise AssertionError('unknown_action')
    except Exception as error:
        emit({'errorType': type(error).__name__, 'reason': str(error)
            if isinstance(error, (RuntimeError, AssertionError)) else 'private_diagnostic_suppressed'})
        raise SystemExit(1)
