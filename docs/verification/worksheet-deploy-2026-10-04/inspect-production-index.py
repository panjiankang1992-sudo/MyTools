#!/usr/bin/env python3
"""精确核对 Cloudflare 注入的脚本及其紧邻换行，不泛化清理正文或空白。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import sys

here = Path(__file__).resolve().parent
namespace = {'__name__': 'readonly_helpers', '__file__': str(here / 'verify-production-readonly.py')}
exec(compile((here / 'verify-production-readonly.py').read_text(), str(here / 'verify-production-readonly.py'), 'exec'), namespace)
manifest = json.loads((here / 'release-files.json').read_text())
expected = next(item for item in manifest['files'] if item['path'] == 'index.html')
response, body = namespace['fetch']('https://toolbox.yuyutian.top', '/', 1024 * 1024, 45, True)
pattern = re.compile(rb'<script\b[^>]*\bsrc=[\'"]https://static\.cloudflareinsights\.com/beacon\.min\.js(?:/[^\'"<>]*)?[\'"][^>]*>\s*</script>', re.I)
matched = list(pattern.finditer(body))
normalized = pattern.sub(b'', body)
candidates = []
if len(matched) == 1:
    offset = matched[0].start()
    # 只尝试去掉注入位置紧邻的一个 LF，不能删除其他位置或多个空白字节。
    for position in [offset - 1, offset]:
        if position >= 0 and normalized[position:position + 1] == b'\n':
            candidate = normalized[:position] + normalized[position + 1:]
            candidates.append({'removedLfOffsetAfterScriptRemoval': position, 'removedByteHex': '0a',
                               'bytes': len(candidate), 'sha256': hashlib.sha256(candidate).hexdigest(),
                               'matchesManifestExactly': hashlib.sha256(candidate).hexdigest() == expected['sha256']})
report = {
    'checkedAt': datetime.now(timezone.utc).isoformat(), 'releaseId': manifest['releaseId'],
    'http': response, 'rawHashMatchesManifest': response['sha256'] == expected['sha256'],
    'manifestBytes': expected['bytes'], 'manifestSha256': expected['sha256'],
    'confirmedInjectedScriptCount': len(matched),
    'script': {'sourceHost': 'static.cloudflareinsights.com', 'sourcePathPrefix': '/beacon.min.js',
               'bytes': len(matched[0].group()), 'sha256': hashlib.sha256(matched[0].group()).hexdigest(),
               'startOffset': matched[0].start()} if len(matched) == 1 else None,
    'afterScriptOnly': {'bytes': len(normalized), 'sha256': hashlib.sha256(normalized).hexdigest()},
    'adjacentSingleLfCandidates': candidates,
    'exactlyMatchesAfterDocumentedInjectionRemoval': response['status'] == 200 and response['mime'] == 'text/html'
       and len(matched) == 1 and any(item['matchesManifestExactly'] for item in candidates),
    'boundary': 'Raw HTTP HTML remains different. Exact removal is limited to one verified Cloudflare beacon empty script tag plus its adjacent single LF byte; no server/CDN settings changed.'
}
with (here / 'http-readonly-retry/index-injection-exact.json').open('x') as handle:
    handle.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
sys.exit(0 if report['exactlyMatchesAfterDocumentedInjectionRemoval'] else 1)
