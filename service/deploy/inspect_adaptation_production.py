#!/usr/bin/env python3
"""Inspect adaptation deployment prerequisites without printing secrets or content."""
import hashlib
import json
from pathlib import Path
import shlex
import subprocess

ROOT = Path('/opt/yuyutian/mytools')
ACCEPTANCE = ROOT / 'runtime/adaptation-acceptance-20260911'


def read_values(path, separator='='):
    """Read private configuration in memory; callers must allowlist all output."""
    values = {}
    for line in path.read_text().splitlines():
        key, delimiter, value = line.strip().partition(separator)
        if delimiter and not key.startswith('#'):
            values[key] = value
    return values


def main():
    """Collect bounded read-only readiness evidence for the operator."""
    env = {key: shlex.split(value)[0] if value else '' for key, value in read_values(ROOT / 'config/services.env').items()}
    report = {'release': (ROOT / 'releases/current').resolve().name, 'roles': {}}
    for role in ('reader', 'scheduler', 'executor'):
        props = read_values(ACCEPTANCE / role / 'application.properties')
        public = {key: value for key, value in props.items() if key.startswith(('executor.cluster', 'executor.labels', 'executor.novel-adaptation.sandbox',
            'executor.novel-adaptation.broker-root', 'executor.novel-adaptation.relay-root', 'executor.script-root', 'executor.python',
            'server.address', 'server.port', 'reader.runtime-base-url', 'task.node-registration', 'task.workload-authorization.executor-identities',
            'task.workload-authorization.reader-identities'))}
        report['roles'][role] = {'keys': sorted(props), 'runtime': public}
    report['configuredUrls'] = {key: env.get(key) for key in ('READER_RUNTIME_BASE_URL', 'TASK_SCHEDULER_URL', 'READER_SERVICE_URL', 'GATEWAY_HTTP_PORT')}
    report['services'] = {}
    for name in ('reader-service', 'task-scheduler-service', 'task-executor-service', 'mytools-gateway', 'reader-runtime'):
        value = subprocess.run(['systemctl', 'show', 'mytools-' + name, '--property=ActiveState,User,FragmentPath'], capture_output=True, text=True)
        report['services'][name] = value.stdout.strip().splitlines()
    import pymysql
    for prefix, schema in (('READER', 'mytools_reader'), ('TASK', 'mytools_task')):
        with pymysql.connect(host=env.get(prefix + '_DB_HOST', '127.0.0.1'), port=int(env.get(prefix + '_DB_PORT', '3306')),
                user=env.get(prefix + '_DB_USER') or env.get(prefix + '_DB_USERNAME'), password=env[prefix + '_DB_PASSWORD'], database=schema) as db, db.cursor() as cursor:
            cursor.execute('SELECT MAX(installed_rank), SUM(NOT success) FROM flyway_schema_history')
            report[schema] = {'migration': cursor.fetchone()}
            if prefix == 'READER':
                cursor.execute('SELECT owner_id,COUNT(*) FROM shelf_book GROUP BY owner_id')
                report[schema]['shelfOwnerCounts'] = cursor.fetchall()
                for table in ('novel_adaptation_provider_deployment', 'reader_adaptation_provider_disclosure', 'novel_chapter_adaptation'):
                    cursor.execute('SELECT COUNT(*) FROM ' + table)
                    report[schema][table] = cursor.fetchone()[0]
            else:
                cursor.execute('SELECT name,status FROM executor_node')
                report[schema]['nodes'] = cursor.fetchall()
                cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
                report[schema]['activeExecutions'] = cursor.fetchone()[0]
    print(json.dumps(report, ensure_ascii=True, default=str))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'errorType': type(error).__name__}))
        raise SystemExit(1)
