#!/usr/bin/env python3
"""只重试首轮失败资源并复核教材；保留首轮失败，单列 CDN 首页注入差异。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import sys
import time

here = Path(__file__).resolve().parent
namespace = {'__name__': 'readonly_helpers', '__file__': str(here / 'verify-production-readonly.py')}
exec(compile((here / 'verify-production-readonly.py').read_text(), str(here / 'verify-production-readonly.py'), 'exec'), namespace)
original = json.loads((here / 'http-readonly-production/report.json').read_text())
base_url = original['plan']['baseUrl']
manifest, published, books, coverage = namespace['read_plan'](here / 'release-files.json', here.parents[2] / 'toolbox-web/content')
assert hashlib.sha256((here / 'release-files.json').read_bytes()).hexdigest() == original['plan']['manifestSha256'], 'Release manifest changed since first run'
output = here / 'http-readonly-retry'
output.mkdir(exist_ok=False)
started = datetime.now(timezone.utc).isoformat()
original_fetch = namespace['fetch']


def fetch_with_transport_retry(origin, path, maximum, timeout, include_body=False):
    """仅重试网络传输未取得 HTTP 响应的失败，最多两次附加请求。"""
    attempts = []
    for index in range(3):
        record, body = original_fetch(origin, path, maximum, timeout, include_body)
        attempts.append(dict(record))
        if record['status'] is not None or not record['error']:
            break
        if index < 2:
            time.sleep(1)
    record['attempts'] = attempts
    return record, body


namespace['fetch'] = fetch_with_transport_retry
manifest_by_path = {'/' + item['path']: item for item in manifest['files']}
retried = [namespace['verify_static'](base_url, manifest_by_path[item['path']], 45)
           for item in original['static'] if not item['passed']]
index = manifest_by_path['/index.html']
root_response, body = fetch_with_transport_retry(base_url, '/', max(index['bytes'] + 1024, 1024 * 1024), 45, True)
# 只识别明确的 Cloudflare Insights 空脚本标签；不修改其他正文、空白或入口。
pattern = re.compile(rb'<script\b[^>]*\bsrc=[\'"]https://static\.cloudflareinsights\.com/beacon\.min\.js(?:/[^\'"<>]*)?[\'"][^>]*>\s*</script>', re.I)
matched = list(pattern.finditer(body))
normalized = pattern.sub(b'', body)
normalization = {
    'rawBytes': len(body), 'rawSha256': hashlib.sha256(body).hexdigest(),
    'expectedBytes': index['bytes'], 'expectedSha256': index['sha256'],
    'rawMatchesManifest': hashlib.sha256(body).hexdigest() == index['sha256'],
    'confirmedInjectionType': 'Cloudflare Insights external beacon script' if matched else None,
    'removedTagCount': len(matched),
    'removedTags': [{'bytes': len(item.group()), 'sha256': hashlib.sha256(item.group()).hexdigest(),
                     'sourceHost': 'static.cloudflareinsights.com', 'sourcePathPrefix': '/beacon.min.js'} for item in matched],
    'normalizedBytes': len(normalized), 'normalizedSha256': hashlib.sha256(normalized).hexdigest(),
    'normalizedMatchesManifestExactly': hashlib.sha256(normalized).hexdigest() == index['sha256'],
    'normalizationRule': 'Remove only the exact matched Cloudflare Insights script tag bytes; no whitespace, attribute or document rewriting'
}
api = namespace['verify_curriculum'](base_url, published, books, coverage, 45)
merged = {item['path']: item for item in original['static']}
merged.update({item['path']: item for item in retried})
non_index = [item for path, item in merged.items() if path != '/index.html']
index_semantics = root_response['status'] == 200 and root_response['mime'] == 'text/html' and len(matched) == 1 and normalization['normalizedMatchesManifestExactly']
summary = {
    'releaseId': manifest['releaseId'], 'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
    'initialReport': '../http-readonly-production/report.json', 'retestedStatic': len(retried),
    'staticExcludingIndex': {'total': len(non_index), 'passed': sum(item['passed'] for item in non_index), 'failed': sum(not item['passed'] for item in non_index)},
    'rawIndexMatchesManifest': normalization['rawMatchesManifest'],
    'indexWithDocumentedEdgeInjectionMatchesManifest': index_semantics,
    'curriculumPassed': api['passed'], 'curriculumBooks': len(api['books']),
    'curriculumEntries': sum(item.get('actualEntries', 0) for item in api['books']),
    'readOnlyChecksPassedWithDocumentedHtmlTransformation': all(item['passed'] for item in non_index) and index_semantics and api['passed'],
    'boundary': 'Original raw HTML hash check remains different; only a separately documented exact Cloudflare beacon removal is compared. No server/CDN mutation or browser interaction.',
    'report': str(output / 'report.json')
}
(output / 'report.json').write_text(json.dumps({'summary': summary, 'retriedStatic': retried, 'rootIndexResponse': root_response,
                                             'indexTransformation': normalization, 'curriculum': api}, ensure_ascii=False, indent=2) + '\n')
(output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(summary, ensure_ascii=False, indent=2))
sys.exit(0 if summary['readOnlyChecksPassedWithDocumentedHtmlTransformation'] else 1)
