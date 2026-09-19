#!/usr/bin/env python3
"""Verify a live adaptation release without accepting consent or calling its model."""
import importlib.util
import json
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = Path('/opt/yuyutian/mytools/runtime/adaptation-production-20260911')
OWNER = 2054944045960138752


def main():
    specification = importlib.util.spec_from_file_location('production_deploy', BASE / 'operator/deploy.py')
    deployment = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(deployment)
    deployment.checked_current(deployment.NEW)
    report = {'release': deployment.NEW.name, 'checks': {}}
    checks = report['checks']
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for port, tls in ((23230, False), (23410, False), (23200, False), (24102, False), (24231, True), (24411, True)):
        deployment.wait_health(port, tls)
    checks['sixHealthEndpoints'] = True
    for port, path in ((23230, '/api/v1/reader-state/features/reader-adaptation?ownerId=' + str(OWNER)),
            (23200, '/api/app/v1/features/reader-adaptation')):
        try:
            opener.open('http://127.0.0.1:' + str(port) + path, timeout=5)
            raise AssertionError('unauthenticated_access')
        except urllib.error.HTTPError as error:
            assert error.code == 401
    checks['unauthenticatedDenied'] = True
    path = '/api/internal/v1/task-execution-authorizations/jwks'
    assert deployment.request(23410, path)[0] in (401, 403)
    assert deployment.request(24411, path, tls=True)[0] == 200
    assert deployment.request(24411, path, tls=True, role='executor')[0] in (401, 403)
    try:
        deployment.request(24411, path, tls=True, client=False)
        raise AssertionError('missing_client_certificate_accepted')
    except (urllib.error.URLError, ssl.SSLError) as error:
        assert isinstance(error, ssl.SSLError) or isinstance(getattr(error, 'reason', None), ssl.SSLError)
    checks['nativeMtlsRequired'] = True
    code, features = deployment.request(23230, '/api/v1/reader-state/features/reader-adaptation?ownerId=' + str(OWNER))
    assert code == 200 and features['readEnabled'] and features['disclosure']
    report['features'] = {key: features[key] for key in ('readEnabled', 'createEnabled', 'consentStatus', 'consentRevision', 'hasActiveConsent')}
    assert features['disclosure']['version'] == 'production-20260911-v1'
    checks['productionDisclosurePublished'] = True
    code, nodes = deployment.request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    selected = {node['name']: node for node in nodes if node['name'] in ('executor-remote-1', deployment.NODE)}
    assert len(selected) == 2 and all(node['status'] == 'ONLINE' for node in selected.values())
    report['nodes'] = {name: {'status': node['status'], 'runningTasks': node['runningTasks']} for name, node in selected.items()}
    with deployment.connection('READER') as db, db.cursor() as cursor:
        cursor.execute('SELECT COUNT(*) FROM shelf_book WHERE owner_id=%s', (OWNER,))
        report['ownerShelfCount'] = cursor.fetchone()[0]
        cursor.execute('SELECT COUNT(*) FROM novel_chapter_adaptation')
        report['adaptationCount'] = cursor.fetchone()[0]
    env = deployment.environment()
    payload = {'taskName': 'system_executor_acceptance', 'idempotencyKey': 'adaptation-production-20260911-http-compatibility-v1',
        'businessType': 'SYSTEM_ACCEPTANCE', 'businessId': deployment.NEW.name, 'parentTaskInstanceId': None,
        'priority': 40, 'parameters': {'scenario': 'success'}, 'requiredNodeLabels': {}}
    headers = {'Content-Type': 'application/json', 'X-Task-Service-Id': 'reader-service',
        'X-Task-Business-Token': env['TASK_BUSINESS_READER_TOKEN']}
    target = 'http://127.0.0.1:23410/api/v1/task-instances'
    call = urllib.request.Request(target, data=json.dumps(payload).encode(), headers=headers, method='POST')
    with opener.open(call, timeout=10) as response:
        receipt = json.load(response)
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        with opener.open(urllib.request.Request(target + '/' + receipt['id'], headers=headers), timeout=10) as response:
            task = json.load(response)
        if task['status'] in ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'):
            break
        time.sleep(1)
    assert task['status'] == 'SUCCEEDED', 'ordinary_http_executor_failed'
    report['ordinaryTask'] = {'id': task['id'], 'status': task['status']}
    checks['ordinaryHttpTaskPreserved'] = True
    changed = {'apps/reader-service.jar', 'apps/task-scheduler-service.jar', 'apps/task-executor-service.jar'}
    unchanged = 0
    for before in deployment.OLD.rglob('*'):
        relative = before.relative_to(deployment.OLD)
        after = deployment.NEW / relative
        if before.is_symlink():
            assert after.is_symlink() and before.readlink() == after.readlink()
        elif before.is_file() and relative.as_posix() not in changed:
            assert after.is_file() and deployment.digest(before) == deployment.digest(after), 'unrelated_release_file_changed'
            unchanged += 1
    report['unchangedReleaseFiles'] = unchanged
    checks['unrelatedReleaseContentPreserved'] = True
    report['modelCallsCreated'] = 0
    report['consentsCreated'] = 0
    deployment.audit('production-preflight', report)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        print(json.dumps({'errorType': type(error).__name__, 'line': traceback.extract_tb(error.__traceback__)[-1].lineno,
            'reason': str(error) if isinstance(error, AssertionError) else 'private_diagnostic_suppressed'}))
        raise SystemExit(1)
