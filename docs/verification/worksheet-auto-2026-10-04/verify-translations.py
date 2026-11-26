"""以临时匿名会话验收公网翻译，不输出或保存 Cookie/CSRF。"""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parent
base = 'https://toolbox.yuyutian.top'
common = {'Origin': base, 'User-Agent': 'Yuyutian-Release-ReadOnly-Check/1.0', 'Content-Type': 'application/json'}


def request(path, body, headers=None):
    try:
        with urlopen(Request(base + '/public/v1' + path, data=json.dumps(body).encode(), headers=common | (headers or {})), timeout=40) as response:
            return response.status, json.load(response), response.headers
    except HTTPError as error:
        return error.code, json.load(error), error.headers


checks = []
status, data, headers = request('/session', {})
assert status == 200
private_headers = {'Cookie': headers['Set-Cookie'].split(';', 1)[0], 'X-Toolbox-Csrf': data['data']['csrfToken']}
status, data, _ = request('/translations', {'items': [{'id': 'one', 'text': 'Monday'}]})
assert status == 401 and data['code'] == 'TOOLBOX_002'
checks.append({'name': 'session-required', 'status': status, 'passed': True})
status, data, _ = request('/translations', {'items': [{'id': 'one', 'text': 'Monday'}]}, {'Cookie': private_headers['Cookie']})
assert status == 403 and data['code'] == 'TOOLBOX_010'
checks.append({'name': 'csrf-required', 'status': status, 'passed': True})
english = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
chinese = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期天']
items = [{'id': 'cn-' + str(index), 'text': word} for index, word in enumerate(chinese)]
items += [{'id': 'en-' + str(index), 'text': word} for index, word in enumerate(english)]
status, data, _ = request('/translations', {'items': items}, private_headers)
assert status == 200
values = data['data']['items']
assert len(values) == 14 and all(item['status'] == 'translated' for item in values)
assert [item['englishText'] for item in values[:7]] == english
assert [item['meaning'] for item in values[7:13]] == chinese[:6]
assert values[13]['meaning'] in ['星期天', '星期日']
checks.append({'name': 'weekdays-both-directions', 'items': values, 'passed': True})
attempts = []
for _ in range(2):
    status, data, _ = request('/translations', {'items': [{'id': 'external-en', 'text': 'butterfly'}, {'id': 'external-cn', 'text': '蝴蝶'}]}, private_headers)
    values = data.get('data', {}).get('items', [])
    attempts.append({'status': status, 'items': values})
    if status == 200 and len(values) == 2 and all(item['status'] == 'translated' for item in values):
        break
else:
    (root / 'translation-failure.json').write_text(json.dumps({'checks': checks, 'attempts': attempts}, ensure_ascii=False, indent=2) + '\n')
    raise AssertionError('General translation failed after retry')
assert values[0]['meaning'] == '蝴蝶' and values[1]['englishText'].lower() == 'butterfly'
checks.append({'name': 'general-translation-api', 'attempts': attempts, 'passed': True})
status, data, _ = request('/translations', {'items': [{'id': 'mixed', 'text': '星期一 Monday'}]}, private_headers)
assert status == 200 and data['data']['items'][0]['status'] == 'failed'
checks.append({'name': 'mixed-source-not-printed-as-answer', 'passed': True})
status, data, _ = request('/translations', {'items': [{'id': 'oversize', 'text': 'a' * 121}]}, private_headers)
assert status in [413, 422] and data['code'] in ['TOOLBOX_001', 'TOOLBOX_005']
checks.append({'name': 'input-bound', 'status': status, 'passed': True})
report = {'passed': True, 'checks': checks, 'credentialsRecorded': False}
(root / 'translation-api.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': True, 'checks': len(checks), 'bidirectionalWeekdayEntries': 14, 'verifiedGeneralTranslations': 2}))
