#!/usr/bin/env python3
"""通过公开 HTTPS MCP 地址验收握手、工具、二进制传输和正式会话刷新。"""
from pathlib import Path
import hashlib
import json
import time
import urllib.request
import urllib.error
from uuid import uuid4
import deploy_pdf_tools as d


def main():
    """从私有文件取认证材料，仅输出脱敏协议及结果摘要。"""
    client = json.loads((d.RUNTIME / 'mcp/client.json').read_text())
    base, token = client['url'], client['token']
    headers = {'User-Agent': 'MyTools-PDF-Acceptance/1.0', 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18'}
    counter = 0
    def rpc(method, params):
        nonlocal counter
        counter += 1
        body = {'jsonrpc': '2.0', 'id': counter, 'method': method, 'params': params}
        with urllib.request.urlopen(urllib.request.Request(base, json.dumps(body).encode(), headers), timeout=120) as response:
            result = json.load(response)
        assert 'error' not in result, 'mcp_rpc_error'
        return result['result']
    def tool(name, arguments):
        result = rpc('tools/call', {'name': name, 'arguments': arguments})
        assert not result.get('isError'), 'mcp_tool_error_' + name
        if 'structuredContent' in result:
            return result['structuredContent']
        return json.loads(result['content'][0]['text'])
    try:
        urllib.request.urlopen(urllib.request.Request(base, b'{}', {'Content-Type': 'application/json', 'User-Agent': 'MyTools-PDF-Acceptance/1.0'}), timeout=20)
        raise AssertionError('anonymous_mcp_allowed')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    init = rpc('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'pdf-production-acceptance', 'version': '1.0'}})
    listed = rpc('tools/list', {})['tools']
    assert len(listed) == 9
    assert not any(x['name'] in ('pdf_upload_local', 'pdf_download_local') for x in listed)
    # 强制触发一次正常刷新路径，验证长期运行不会依赖首次短期令牌。
    path = d.RUNTIME / 'mcp/accounts.json'
    mapping = json.loads(path.read_text())
    for account in mapping.values():
        account['expiresAt'] = 0
    d.private(path, json.dumps(mapping), 'mytools')
    tool('pdf_capabilities', {})
    assert all(a['expiresAt'] > time.time() for a in json.loads(path.read_text()).values())
    data = Path('/tmp/pdf-acceptance-input.pdf').read_bytes()
    upload = tool('pdf_create_upload', {'name': 'mcp-synthetic.pdf', 'bytes_count': len(data), 'media_type': 'application/pdf', 'idempotency_key': str(uuid4())})
    upload_headers = {'User-Agent': 'MyTools-PDF-Acceptance/1.0', 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/octet-stream', 'X-Upload-Offset': '0'}
    with urllib.request.urlopen(urllib.request.Request(upload['uploadUrl'], data, upload_headers, method='PUT'), timeout=30) as response:
        assert response.status == 200
    tool('pdf_complete_upload', {'input_id': upload['input']['id']})
    def wait(kind, identity):
        deadline = time.monotonic() + 360
        while time.monotonic() < deadline:
            row = tool('pdf_get', {'kind': kind, 'record_id': identity})
            if row['status'] == 'READY':
                return row
            assert row['status'] not in ('FAILED', 'CANCELLED', 'EXPIRED'), 'mcp_work_failed'
            time.sleep(2)
        raise RuntimeError('mcp_work_timeout')
    wait('inputs', upload['input']['id'])
    plan = tool('pdf_plan', {'operation': 'PDF_PAGE_CUT', 'options': {'axis': 'VERTICAL', 'position': .5}, 'input_ids': [upload['input']['id']], 'idempotency_key': str(uuid4())})
    wait('plans', plan['id'])
    job = tool('pdf_start', {'plan_id': plan['id'], 'idempotency_key': str(uuid4())})
    assert job['result']['pageCount'] == 4
    artifacts = tool('pdf_artifacts', {'job_id': job['id']})
    item = artifacts['artifacts'][0]
    with urllib.request.urlopen(urllib.request.Request(item['downloadUrl'], headers={'Authorization': 'Bearer ' + token, 'User-Agent': 'MyTools-PDF-Acceptance/1.0'}), timeout=60) as response:
        data = response.read()
    assert hashlib.sha256(data).hexdigest() == item['sha256']
    report = {'passed': True, 'workerVersion': '1.0.1', 'url': base, 'protocolVersion': init['protocolVersion'], 'toolCount': len(listed), 'jobId': job['id'], 'outputPages': 4, 'checks': ['HTTPS', 'anonymous 401', 'initialize', 'tools/list', 'dedicated account refresh', 'uploadUrl PUT', 'frozen plan', 'downloadUrl GET', 'SHA256']}
    d.private(d.RUNTIME / 'acceptance/mcp.json', json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
