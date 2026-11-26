#!/usr/bin/env python3
"""真实生产验收：正式身份、网关、调度、执行器、存储和 MCP，输出仅含脱敏结果。"""
from pathlib import Path
import hashlib
import json
import secrets
import sys
import time
import urllib.request
import urllib.error
from uuid import uuid4
import deploy_pdf_tools as d

BASE = 'http://127.0.0.1:23200'
PRIVATE = d.RUNTIME / 'mcp'


def api(method, path, body=None, token=None, key=None, offset=None, raw=False, base=BASE):
    """通过真实 HTTP 调用接口，不回显认证材料。"""
    headers = {'Content-Type': 'application/octet-stream' if isinstance(body, bytes) else 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    if offset is not None:
        headers['X-Upload-Offset'] = str(offset)
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=120) as response:
        if raw:
            return response.read()
        result = json.load(response)
        return result.get('data', result)


def provision():
    """通过身份服务正式导入专用账户并保存独立 MCP 令牌。"""
    import bcrypt
    accounts = []
    config = d.values(d.ROOT / 'config/services.env')
    for index in range(2):
        path = d.CONFIG / ('acceptance-account-' + str(index) + '.json')
        if path.exists():
            account = json.loads(path.read_text())
        else:
            account = {'id': secrets.randbelow(10**14) + 8 * 10**14, 'username': 'pdf-agent-' + secrets.token_hex(6), 'password': secrets.token_urlsafe(32)}
            api('POST', '/internal/v1/identity/users', {'id': account['id'], 'externalUserId': account['username'], 'username': account['username'], 'passwordHash': bcrypt.hashpw(account['password'].encode(), bcrypt.gensalt()).decode(), 'status': 'ACTIVE', 'credentialVersion': 1, 'roles': ['USER']}, token=config['IDENTITY_INTERNAL_TOKEN'], base='http://127.0.0.1:23290')
            d.private(path, json.dumps(account))
        pair = api('POST', '/api/app/v1/identity/login', {'username': account['username'], 'password': account['password'], 'deviceId': 'pdf-production-acceptance'})
        account['token'] = pair['accessToken']
        accounts.append(account)
        if index == 0:
            token_file = PRIVATE / 'client.json'
            client = json.loads(token_file.read_text()) if token_file.exists() else {'url': 'https://mytools.yuyutian.top/api/app/v1/pdf-mcp', 'token': secrets.token_urlsafe(40)}
            d.private(token_file, json.dumps(client), 'mytools')
            mapping = {hashlib.sha256(client['token'].encode()).hexdigest(): {'gatewayToken': pair['accessToken'], 'refreshToken': pair['refreshToken'], 'expiresAt': time.time() + pair['expiresIn']}}
            d.private(PRIVATE / 'accounts.json', json.dumps(mapping), 'mytools')
    gateway = d.values(d.CONFIG / 'gateway.env')
    gateway['PDF_OWNER_ALLOWLIST'] = ','.join(str(a['id']) for a in accounts)
    d.environment('gateway', gateway)
    d.command('systemctl', 'restart', 'mytools-mytools-gateway')
    d.health(23200)
    d.private(d.CONFIG / 'acceptance-sessions.json', json.dumps(accounts))
    print(json.dumps({'accountsProvisioned': 2, 'mcpCredentialsFile': str(PRIVATE / 'client.json')}), flush=True)


def accept():
    """覆盖八项核心能力、原文件四页结果和账户隔离。"""
    accounts = json.loads((d.CONFIG / 'acceptance-sessions.json').read_text())
    token = accounts[0]['token']
    prefix = '/api/app/v1/pdf'
    out = d.RUNTIME / 'acceptance'
    out.mkdir(exist_ok=True, mode=0o700)
    report = {'passed': False, 'fixture': 'synthetic two-page PDF; no user document uploaded', 'workerVersion': '1.0.1', 'operations': [], 'checks': []}
    def save():
        d.private(out / 'acceptance.json', json.dumps(report, indent=2))
    def call(method, path, body=None, **kwargs):
        return api(method, prefix + path, body, token=token, **kwargs)
    def wait(kind, record_id):
        deadline = time.monotonic() + 360
        while time.monotonic() < deadline:
            row = call('GET', '/' + kind + '/' + record_id)
            if row['status'] == 'READY':
                return row
            if row['status'] in ('FAILED', 'CANCELLED', 'EXPIRED'):
                print(json.dumps({'failedRecord': record_id, 'kind': kind, 'status': row['status'], 'error': row.get('errorCode')}), flush=True)
                raise RuntimeError('record_not_ready')
            time.sleep(2)
        raise RuntimeError('record_timeout_' + record_id)
    def upload(data, name, mime):
        body = {'name': name, 'mediaType': mime, 'bytes': len(data)}
        key = str(uuid4())
        row = call('POST', '/uploads', body, key=key)
        assert call('POST', '/uploads', body, key=key)['id'] == row['id']
        for offset in range(0, len(data), 1048576):
            chunk = data[offset:offset + 1048576]
            call('PUT', f'/uploads/{row["id"]}/content', chunk, offset=offset)
            call('PUT', f'/uploads/{row["id"]}/content', chunk, offset=offset)
        call('POST', f'/uploads/{row["id"]}/complete', {})
        return wait('inputs', row['id'])
    def plan(operation, ids=None, options=None, content=None, attachments=None):
        body = {'operation': operation, 'options': options or {}}
        for key, value in [('inputIds', ids), ('content', content), ('attachmentIds', attachments)]:
            if value is not None:
                body[key] = value
        row = call('POST', '/plans', body, key=str(uuid4()))
        ready = wait('plans', row['id'])
        key = str(uuid4())
        job = call('POST', '/jobs', {'planId': row['id']}, key=key)
        assert call('POST', '/jobs', {'planId': row['id']}, key=key)['id'] == job['id']
        assert job['status'] == 'SUCCEEDED'
        report['operations'].append({'operation': operation, 'jobId': job['id'], 'pageCount': ready['result'].get('pageCount')})
        save()
        print(json.dumps({'operation': operation, 'status': job['status']}), flush=True)
        return job
    def download(job, index=0):
        data = call('GET', f'/jobs/{job["id"]}/artifacts/{index}/content', raw=True)
        assert hashlib.sha256(data).hexdigest() == job['result']['artifacts'][index]['sha256']
        return data
    capabilities = call('GET', '/capabilities')
    report['capabilities'] = capabilities
    try:
        api('GET', prefix + '/capabilities')
        raise AssertionError('anonymous_allowed')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    original = upload(Path('/tmp/pdf-acceptance-input.pdf').read_bytes(), 'first-unit.pdf', 'application/pdf')
    cut = plan('PDF_PAGE_CUT', [original['id']], {'axis': 'VERTICAL', 'position': .5})
    assert original['result']['pageCount'] == 2 and cut['result']['pageCount'] == 4
    data = download(cut)
    (out / 'result.pdf').write_bytes(data)
    report.update(inputPages=2, outputPages=4, sha256=hashlib.sha256(data).hexdigest(), originalInputId=original['id'])
    try:
        api('GET', prefix + '/jobs/' + cut['id'], token=accounts[1]['token'])
        raise AssertionError('foreign_owner_allowed')
    except urllib.error.HTTPError as error:
        assert error.code == 404
    plan('PDF_SPLIT', [original['id']], {'pagesPerFile': 1})
    plan('PDF_MERGE', [original['id'], original['id']])
    images = plan('PDF_TO_IMAGES', [original['id']], {'dpi': 72, 'format': 'PNG'})
    image = upload(download(images), 'page.png', 'image/png')
    plan('IMAGES_TO_PDF', [image['id']], {'paperSize': 'A4', 'fit': 'FIT'})
    text = plan('TEXT_TO_PDF', content={'format': 'TEXT', 'text': 'Chinese acceptance: \u4e2d\u6587\u6587\u5b57\u3002'})
    text_input = upload(download(text), 'text.pdf', 'application/pdf')
    extracted = plan('PDF_TO_TEXT', [text_input['id']])
    assert '\u4e2d\u6587\u6587\u5b57' in extracted['result']['textPreview']
    markdown = plan('MARKDOWN_TO_PDF', content={'format': 'MARKDOWN', 'text': '# \u4e2d\u6587\u9a8c\u6536\n\n[Link](https://example.com)\n\n![page](attachment:' + image['id'] + ')'}, attachments=[image['id']])
    (out / 'markdown.pdf').write_bytes(download(markdown))
    cancel = call('POST', '/plans', {'operation': 'PDF_PAGE_CUT', 'inputIds': [original['id']], 'options': {'axis': 'VERTICAL', 'position': .5}}, key=str(uuid4()))
    assert call('POST', '/plans/' + cancel['id'] + '/cancel', {})['status'] == 'CANCELLED'
    report['checks'] = ['real MySQL Scheduler Executor Storage Asset', 'duplicate chunk', 'idempotency', 'anonymous 401', 'foreign owner 404', 'SHA256 download', 'Chinese font extraction', 'managed Markdown attachment', 'cancel']
    report['passed'] = True
    save()
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    {'provision': provision, 'accept': accept}[sys.argv[1]]()
