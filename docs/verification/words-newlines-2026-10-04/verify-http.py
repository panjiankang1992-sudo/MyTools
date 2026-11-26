"""只读校验本轮变更的线上文件和教材目录，记录 CDN 对首页的精确插入。"""
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path
import json
import re
import time
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parent
manifest = json.loads((root / 'release-files.json').read_text())
changed = json.loads((root / 'changed-files.json').read_text())['files']
base = 'https://toolbox.yuyutian.top'

def check(item):
    attempts = []
    for attempt in range(3):
        try:
            with urlopen(Request(base + '/' + item['path'], headers={'Cache-Control': 'no-cache', 'Accept-Encoding': 'identity', 'User-Agent': 'Yuyutian-Release-ReadOnly-Check/1.0'}), timeout=20) as response:
                body = response.read()
                mime = response.headers.get_content_type()
                result = {'path': item['path'], 'status': response.status, 'mime': mime, 'bytes': len(body), 'sha256': sha256(body).hexdigest()}
            expected_mime = {'js': ['application/javascript', 'text/javascript'], 'css': ['text/css'], 'html': ['text/html']}[item['path'].rsplit('.', 1)[-1]]
            passed = result['status'] == 200 and mime in expected_mime and result['sha256'] == item['sha256'] and len(body) == item['bytes']
            if item['path'] == 'index.html':
                (root / 'public-index.html').write_bytes(body)
                pattern = re.compile(rb'<script\b[^>]*\bsrc=[\'"]https://static\.cloudflareinsights\.com/beacon\.min\.js(?:/[^\'"<>]*)?[\'"][^>]*>\s*</script>', re.I)
                matches = list(pattern.finditer(body))
                result['rawMatches'] = passed
                if not passed and len(matches) == 1:
                    match = matches[0]
                    # CDN 在闭合标签前插入脚本；只允许移除该脚本及紧邻的一字节换行。
                    candidates = [('script-only', body[:match.start()] + body[match.end():])]
                    if body[match.end():match.end()+1] == b'\n':
                        candidates.append(('script-and-following-LF', body[:match.start()] + body[match.end()+1:]))
                    if body[max(0,match.start()-1):match.start()] == b'\n':
                        candidates.append(('script-and-preceding-LF', body[:match.start()-1] + body[match.end():]))
                    for rule, value in candidates:
                        if len(value) == item['bytes'] and sha256(value).hexdigest() == item['sha256']:
                            result['edgeTransformation'] = rule
                            result['normalizedSha256'] = sha256(value).hexdigest()
                            passed = result['status'] == 200 and mime == 'text/html'
                            break
            result['passed'] = passed
            attempts.append(result)
            if passed:
                return {'path': item['path'], 'passed': True, 'attempts': attempts}
        except Exception as error:
            attempts.append({'path': item['path'], 'error': str(error), 'passed': False})
        if attempt < 2:
            time.sleep(1)
    return {'path': item['path'], 'passed': False, 'attempts': attempts}

with ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(check, changed))
try:
    with urlopen(Request(base + '/public/v1/textbooks', headers={'User-Agent':'Yuyutian-Release-ReadOnly-Check/1.0'}), timeout=20) as response:
        catalog = json.load(response)
        editions = catalog.get('data', {}).get('editions')
        api = {'status': response.status, 'editions': len(editions) if isinstance(editions, list) else None, 'passed': response.status == 200 and isinstance(editions, list) and len(editions) > 0}
except Exception as error:
    api = {'error': str(error), 'passed': False}
report = {'releaseId': manifest['releaseId'], 'checkedChangedFiles': len(results), 'passedFiles': sum(item['passed'] for item in results), 'static': results, 'catalog': api}
report['passed'] = all(item['passed'] for item in results) and api['passed']
(root / 'http.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({key: value for key, value in report.items() if key != 'static'}, ensure_ascii=False))
raise SystemExit(0 if report['passed'] else 1)
