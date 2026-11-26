#!/usr/bin/env python3
"""从开发机验证公网真实认证，验收令牌仅经 SSH 管道进入内存。"""
import json
from pathlib import Path
import subprocess

BASE = 'https://mytools.yuyutian.top'
REMOTE_LOGIN = r'''
import pathlib,json,urllib.request
p=pathlib.Path('/opt/yuyutian/mytools/config/pdf-tools/acceptance-account-0.json')
a=json.loads(p.read_text())
assert a['username'].startswith('pdf-agent-')
body={'username':a['username'],'password':a['password'],'deviceId':'rss-public-route-acceptance'}
request=urllib.request.Request('http://127.0.0.1:23200/api/app/v1/identity/login',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=15) as response:
 result=json.load(response);pair=result.get('data',result)
print(json.dumps({'accessToken':pair['accessToken']}))
'''


def request(path, token=None, body=None):
    """认证头经标准输入传给 curl，不写入命令参数或磁盘。"""
    config = ['url = ' + json.dumps(BASE + path), 'silent', 'show-error', 'max-time = 30',
              'write-out = "\\n%{http_code}"', 'header = "Accept: application/json"']
    if token:
        config.append('header = ' + json.dumps('Authorization: Bearer ' + token))
    if body is not None:
        config.extend(['request = "POST"', 'header = "Content-Type: application/json"',
                       'data-binary = ' + json.dumps(json.dumps(body))])
    result = subprocess.run(['curl', '--config', '-'], input='\n'.join(config) + '\n',
                            capture_output=True, text=True, timeout=35)
    assert result.returncode == 0, 'public_transport_failed'
    raw, status = result.stdout.rsplit('\n', 1)
    try:
        data = json.loads(raw) if raw else {}
    except ValueError:
        data = {}
    return int(status), data.get('data', data) if isinstance(data, dict) else data


def main():
    """执行公网只读与发现验证，最终注销专用会话。"""
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', 'home-ubuntu', 'sudo -n python3 -'],
                            input=REMOTE_LOGIN, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, 'acceptance_login_failed'
    token = json.loads(result.stdout)['accessToken']
    report = {'passed': False, 'checks': []}
    try:
        for prefix in ['/api/app/v1/rss', '/api/v1/rss']:
            status, _ = request(prefix + '/capabilities')
            assert status == 401, 'anonymous_public_request_not_rejected'
            status, capabilities = request(prefix + '/capabilities', token)
            assert status == 200 and capabilities['pollingEnabled'], 'public_authenticated_route_unavailable'
        report['checks'].append('both_public_prefixes_authenticated_and_polling_enabled')
        for path in ['/api/app/v1/identity/me', '/api/app/v1/rss/counts', '/api/app/v1/rss/subscriptions']:
            status, _ = request(path, token)
            assert status == 200, 'public_read_failed'
        report['checks'].append('real_identity_and_rss_reads')
        status, preview = request('/api/app/v1/rss/discover', token, {'url': 'https://sspai.com/feed'})
        assert status == 200 and preview.get('preview', {}).get('entries'), 'public_discovery_failed'
        report['checks'].append('public_post_discovers_real_feed')
        report['passed'] = True
    finally:
        status, _ = request('/api/app/v1/identity/logout', token, {})
        assert status in (200, 204), 'public_logout_failed'
        report['testSessionRevoked'] = True
        destination = Path(__file__).with_name('production-public-verification.json')
        destination.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        print(json.dumps({'error': type(error).__name__, 'passed': False}), flush=True)
        raise SystemExit(1) from None
