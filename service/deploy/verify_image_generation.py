#!/usr/bin/env python3
"""通过图片业务服务、真实 Scheduler、模型及资产登记验证上线链路。"""
import importlib.util
import json
from pathlib import Path
import time
import hashlib

spec = importlib.util.spec_from_file_location('deploy_image', '/tmp/deploy_image_generation.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
OWNER = 900000000000000001
OTHER = 900000000000000002
PREFIX = '/internal/v1/images'


def api(path, method='GET', body=None, owner=OWNER, expected=200):
    code, data = d.request(23340, PREFIX + path, method, body, owner)
    assert code == expected, 'image_http_' + str(code)
    return data


def main():
    models = api('/models')
    assert next(m for m in models if m['id'] == 'krea2-local')['status'] == 'READY'
    assert next(m for m in models if m['id'] == 'sillytraven-remote')['status'] == 'UNVERIFIED'
    params = {'resourceId': 'krea2-local', 'prompt': 'A ceramic tea cup on a pale stone table, soft daylight, professional product photography.',
        'mode': 'TEXT_TO_IMAGE', 'references': [], 'size': '1024x1024', 'count': 1, 'seed': 812,
        'idempotencyKey': 'image-production-acceptance-v2'}
    job = api('/jobs', 'POST', params)
    assert api('/jobs', 'POST', params)['id'] == job['id'], 'idempotency_failed'
    api('/jobs/' + job['id'], owner=OTHER, expected=404)
    started = time.monotonic()
    previous = None
    while time.monotonic() - started < 360:
        job = api('/jobs/' + job['id'])
        if job['status'] != previous:
            d.emit({'jobId': job['id'], 'status': job['status'], 'errorCode': job.get('errorCode')})
            previous = job['status']
        if job['status'] in ['SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIAL_SUCCESS', 'UNCONFIRMED']:
            break
        time.sleep(3)
    assert job['status'] == 'SUCCEEDED', 'job_not_successful'
    raw = api('/jobs/' + job['id'] + '/images/0')
    assert raw.startswith(b'\x89PNG\r\n\x1a\n') and len(raw) > 10000
    api('/jobs/' + job['id'] + '/images/0', owner=OTHER, expected=404)
    d.run(['systemctl', 'restart', 'mytools-image-generation-service'])
    d.health(23340)
    assert api('/jobs/' + job['id'])['status'] == 'SUCCEEDED'
    assert api('/jobs/' + job['id'] + '/images/0') == raw
    assert any(j['id'] == job['id'] for j in api('/works?page=0'))
    reference = api('/jobs/' + job['id'] + '/images/0/reference', 'POST', {})
    api('/uploads/' + reference['id'], owner=OTHER, expected=404)
    params['idempotencyKey'] = 'image-production-cancel-v2'
    cancelled = api('/jobs', 'POST', params)
    api('/jobs/' + cancelled['id'] + '/cancel', 'POST', {})
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        cancelled = api('/jobs/' + cancelled['id'])
        if cancelled['status'] == 'CANCELLED':
            break
        time.sleep(2)
    assert cancelled['status'] == 'CANCELLED'
    report = {'jobId': job['id'], 'assetId': job['result']['images'][0]['assetId'], 'pngBytes': len(raw),
        'sha256': hashlib.sha256(raw).hexdigest(), 'elapsedSeconds': round(time.monotonic() - started, 2),
        'idempotency': True, 'ownerIsolation': True, 'assetRegistered': True, 'historyReadable': True,
        'referenceIsolation': True, 'restartPersistence': True, 'cancelledJobId': cancelled['id'], 'cancelled': True}
    d.write(d.STATE / 'acceptance.json', json.dumps(report))
    d.emit(report)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        d.emit({'failed': True, 'errorType': type(error).__name__, 'reason': str(error) if isinstance(error, AssertionError) else 'private_diagnostic_suppressed'})
        raise SystemExit(1)
