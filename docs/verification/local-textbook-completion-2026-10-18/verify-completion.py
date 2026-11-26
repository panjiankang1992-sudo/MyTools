"""使用临时匿名会话验证教材优先补全，不新增或修改教材内容。"""
from pathlib import Path
import json
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
BASE = 'https://toolbox.yuyutian.top'


def request(path, body, folder, csrf=None):
    """凭据只存于临时文件，不打印，不放进命令行参数。"""
    headers = Path(folder) / 'headers'
    headers.write_text('Content-Type: application/json\nAccept: application/json\n'
                       + ('X-Toolbox-Csrf: ' + csrf + '\n' if csrf else ''))
    os.chmod(headers, 0o600)
    jar = Path(folder) / 'cookies'
    result = subprocess.run([
        'curl', '--silent', '--show-error', '--max-time', '45',
        '--cookie', str(jar), '--cookie-jar', str(jar),
        '--header', '@' + str(headers), '--data-binary', '@-',
        '--write-out', '\n%{http_code}', BASE + path,
    ], input=json.dumps(body).encode(), capture_output=True, check=True)
    raw, code = result.stdout.rsplit(b'\n', 1)
    assert code == b'200', 'Completion endpoint failed, HTTP ' + code.decode()
    envelope = json.loads(raw)
    assert envelope['code'] == 'OK', 'Completion response failed'
    return envelope['data']


def main():
    """核验双向释义、释义别名、教材拼音和本地未命中，报告不含会话信息。"""
    checks = []
    with tempfile.TemporaryDirectory(prefix='toolbox-completion-') as folder:
        session = request('/public/v1/session', {}, folder)
        csrf = session['csrfToken']
        english = request('/public/v1/translations', {'items': [
            {'id': 'en', 'text': 'dancing'},
            {'id': 'alias', 'text': '明天'},
            {'id': 'phrase', 'text': '放学后'},
        ]}, folder, csrf)['items']
        by_id = {item['id']: item for item in english}
        assert all(item['status'] == 'translated' for item in english)
        assert by_id['en']['meaning'] == '跳舞，舞蹈'
        assert by_id['alias']['englishText'] == 'tomorrow'
        assert by_id['alias']['meaning'] == '明天；在明天'
        assert by_id['phrase']['englishText'] == 'after school'
        checks += [{'case': 'english-textbook-pair', 'passed': True},
                   {'case': 'chinese-meaning-alias', 'passed': True},
                   {'case': 'phrase-reverse-lookup', 'passed': True}]
        chinese = request('/public/v1/translations', {'subject': 'chinese', 'items': [
            {'id': 'reading', 'text': '一'},
            {'id': 'missing', 'text': '不存在的拼音字帖内容'},
        ]}, folder, csrf)['items']
        by_id = {item['id']: item for item in chinese}
        assert by_id['reading']['status'] == 'translated'
        assert by_id['reading']['pinyinSegments'] == [{'grapheme': '一', 'syllable': 'yī'}]
        assert by_id['missing']['status'] == 'failed'
        assert not by_id['missing'].get('pinyinSegments')
        checks += [{'case': 'chinese-textbook-reading', 'passed': True},
                   {'case': 'chinese-local-miss-no-fabricated-result', 'passed': True}]
    report = {'site': BASE, 'passed': len(checks), 'checks': checks,
              'textbookWrites': False, 'credentialsSaved': False}
    (ROOT / 'public-completion.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
