#!/usr/bin/env python3
"""验收记录与实际版本一致后开放 PDF APP 入口，并保存脱敏发布报告。"""
import json
import sys
import time
import pymysql
import deploy_pdf_tools as d


def main():
    """核对八项能力、MCP、节点、任务版本与服务健康后解除灰度名单。"""
    acceptance = json.loads((d.RUNTIME / 'acceptance/acceptance.json').read_text())
    mcp = json.loads((d.RUNTIME / 'acceptance/mcp.json').read_text())
    assert acceptance['passed'] and len(acceptance['operations']) == 8
    assert mcp['passed'] and mcp['toolCount'] == 9
    assert acceptance['workerVersion'] == mcp['workerVersion'] == '1.0.1'
    config = d.values(d.ROOT / 'config/services.env')
    with pymysql.connect(host=config.get('TASK_DB_HOST', '127.0.0.1'), port=int(config.get('TASK_DB_PORT', '3306')), user=config.get('TASK_DB_USER') or config['TASK_DB_USERNAME'], password=config['TASK_DB_PASSWORD'], database='mytools_task') as db, db.cursor() as cursor:
        cursor.execute("SELECT s.script_version,d.enabled,c.enabled FROM task_step_definition s JOIN task_definition d ON d.id=s.task_definition_id JOIN execution_cluster c ON c.id=d.cluster_id WHERE d.name='pdf_process'")
        row = cursor.fetchone()
        assert row == ('1.0.1', 1, 1), 'unexpected_task_version'
    nodes = d.request('/api/v1/execution-topology/nodes')
    pdf = next(n for n in nodes if n['name'] == 'pdf-executor-1')
    assert pdf['status'] == 'ONLINE' and 'pdf_process:1.0.1' in pdf.get('capabilities', {}).get('scriptReleases', {})
    for port in (23200, 23342, 23410, 23240, 23270, 23290):
        d.health(port)
    gateway = d.values(d.CONFIG / 'gateway.env')
    if '--publish' in sys.argv:
        gateway['PDF_OWNER_ALLOWLIST'] = ''
        d.environment('gateway', gateway)
        d.command('systemctl', 'restart', 'mytools-mytools-gateway')
        d.health(23200)
    else:
        assert '--verify-only' in sys.argv, 'explicit_mode_required'
    services = {name: d.command('systemctl', 'is-active', 'mytools-' + name) for name in ('pdf-service', 'pdf-executor-service', 'pdf-mcp-service', 'mytools-gateway', 'task-scheduler-service', 'task-executor-service')}
    assert set(services.values()) == {'active'}
    report = {'publishedAtEpoch': int(time.time()), 'pdfAppRouteEnabled': True, 'ownerAllowlistRemoved': not bool(gateway.get('PDF_OWNER_ALLOWLIST')), 'workerVersion': '1.0.1', 'mcpUrl': mcp['url'], 'services': services, 'otherExecutors': [{'name': n['name'], 'status': n['status']} for n in nodes if n['name'] != 'pdf-executor-1'], 'fixture': acceptance['fixture'], 'appDeviceAcceptance': 'pending_no_connected_device'}
    d.private(d.RUNTIME / 'acceptance/deployment.json', json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
