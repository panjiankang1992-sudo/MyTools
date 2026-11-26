#!/usr/bin/env python3
"""按用户授权恢复昨晚十三条未执行的 X 排队超时任务，保留原下载与调度终态并审计自动化重绑。"""
from pathlib import Path
import json
import subprocess
from uuid import NAMESPACE_URL, uuid5
from urllib.request import Request, urlopen

import pymysql

RECOVERY = 'x-dispatch-timeouts-20261021-v1'
START, END = '2026-10-20 18:00:00', '2026-10-21 08:00:00'


def main():
    """通过 Download API 重跑，仅用自动化专属账号事务恢复原消息跟踪与完成回执。"""
    pid = subprocess.check_output(['systemctl', 'show', 'mytools-task-scheduler-service',
                                   '-p', 'MainPID', '--value'], text=True).strip()
    environment = dict(value.split('=', 1) for value in
                       Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)

    def connect(database, prefix):
        """各数据库只使用该服务账号，Scheduler 连接仅用于 SELECT。"""
        user_key = prefix + '_DB_' + ('USERNAME' if prefix == 'TASK' else 'USER')
        return pymysql.connect(host='127.0.0.1', user=environment[user_key],
                               password=environment[prefix + '_DB_PASSWORD'], database=database,
                               cursorclass=pymysql.cursors.DictCursor)

    task = connect('mytools_task', 'TASK')
    download = connect('mytools_download', 'DOWNLOAD')
    automation = connect('mytools_message_automation', 'MESSAGE_AUTOMATION')
    mappings = []
    try:
        with task.cursor() as cursor:
            cursor.execute('SET TRANSACTION READ ONLY')
            cursor.execute("""SELECT id FROM task_instance WHERE task_name = %s AND status = %s
                AND created_at >= %s AND created_at < %s AND NOT EXISTS (
                    SELECT 1 FROM task_execution x WHERE x.task_instance_id = task_instance.id)
                ORDER BY created_at""", ('download_x_post', 'TIMED_OUT', START, END))
            tasks = cursor.fetchall()
        assert len(tasks) == 13, 'unexpected_recovery_scope'
        for original in tasks:
            with download.cursor() as cursor:
                cursor.execute('SELECT * FROM download_request WHERE task_instance_id = %s', (original['id'],))
                old = cursor.fetchone()
                assert old and old['request_kind'] == 'X_POST'
                cursor.execute('SELECT COUNT(*) AS n FROM download_item WHERE download_request_id = %s', (old['id'],))
                assert cursor.fetchone()['n'] == 0, 'original_request_has_files'
            key = RECOVERY + ':' + old['id']
            audit_id = str(uuid5(NAMESPACE_URL, key))
            with automation.cursor() as cursor:
                cursor.execute('SELECT payload_json FROM automation_outbox WHERE id = %s', (audit_id,))
                audited = cursor.fetchone()
            automation.rollback()
            if audited:
                payload = audited['payload_json']
                mappings.append(json.loads(payload) if isinstance(payload, str) else payload)
                continue
            with automation.cursor() as cursor:
                cursor.execute("""SELECT a.id AS action_id, a.automation_run_id AS run_id,
                    a.status AS action_status, a.error_code AS action_error, r.status AS run_status,
                    r.error_code AS run_error, r.inbound_message_id AS message_id
                    FROM automation_action a JOIN automation_run r ON r.id = a.automation_run_id
                    WHERE a.external_request_id = %s""", (old['id'],))
                binding = cursor.fetchone()
                assert binding and binding['action_status'] == 'FAILED' and binding['run_status'] == 'FAILED'
                cursor.execute('SELECT COUNT(*) AS n FROM automation_action WHERE automation_run_id = %s',
                               (binding['run_id'],))
                assert cursor.fetchone()['n'] == 1, 'mixed_run_requires_explicit_recovery'
            automation.rollback()
            parameters = old['parameters_json']
            parameters = json.loads(parameters) if isinstance(parameters, str) else dict(parameters)
            body = {'ownerId': old['owner_id'], 'idempotencyKey': key, 'sourceType': 'RECOVERY',
                    'sourceKey': key, 'requestKind': 'X_POST', 'parameters': parameters}
            request = Request('http://127.0.0.1:23220/api/v1/download-requests',
                              data=json.dumps(body).encode(), headers={
                                  'Authorization': 'Bearer ' + environment['DOWNLOAD_INTERNAL_TOKEN'],
                                  'Content-Type': 'application/json'})
            with urlopen(request, timeout=30) as response:
                new = json.load(response)
            new_task_id = new.get('task_instance_id') or new.get('taskInstanceId')
            assert new['id'] != old['id'] and new_task_id, 'invalid_retry_binding'
            mapping = {'recoveryKey': key, 'originalTaskId': original['id'], 'oldDownloadRequestId': old['id'],
                       'newDownloadRequestId': new['id'], 'newTaskId': new_task_id,
                       'runId': binding['run_id'], 'messageId': binding['message_id'],
                       'actionId': binding['action_id'], 'oldRunStatus': binding['run_status'],
                       'oldRunError': binding['run_error'], 'oldActionStatus': binding['action_status'],
                       'oldActionError': binding['action_error']}
            automation.begin()
            try:
                with automation.cursor() as cursor:
                    cursor.execute('SELECT status FROM automation_run WHERE id = %s FOR UPDATE', (binding['run_id'],))
                    assert cursor.fetchone()['status'] == 'FAILED', 'run_changed_during_recovery'
                    cursor.execute("""UPDATE automation_action SET external_request_id = %s, status = %s,
                        error_code = NULL, last_progress_percent = -1, submission_attempts = 0,
                        poll_failure_attempts = 0, next_attempt_at = NULL, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = %s AND automation_run_id = %s AND external_request_id = %s AND status = %s""",
                                   (new['id'], 'RUNNING', binding['action_id'], binding['run_id'], old['id'], 'FAILED'))
                    assert cursor.rowcount == 1, 'action_changed_during_recovery'
                    cursor.execute("""UPDATE automation_run SET status = %s, error_code = NULL,
                        action_refs_json = %s, reconcile_claimed_by = NULL, reconcile_claim_until = NULL,
                        reconciliation_failures = 0, next_reconcile_at = NULL, last_reconciliation_error = NULL,
                        updated_at = CURRENT_TIMESTAMP(6) WHERE id = %s AND status = %s""",
                                   ('RUNNING', json.dumps([new['id']]), binding['run_id'], 'FAILED'))
                    assert cursor.rowcount == 1
                    # 专用审计事件由本恢复执行器同步消费，不进入机器人完成消息的发送队列。
                    cursor.execute("""INSERT INTO automation_outbox
                        (id, aggregate_id, event_type, payload_json, created_at, published_at)
                        VALUES (%s, %s, %s, %s, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))""",
                                   (audit_id, binding['run_id'], 'AutomationRunRecoveryRecorded', json.dumps(mapping)))
                automation.commit()
            except Exception:
                automation.rollback()
                raise
            mappings.append(mapping)
            print(json.dumps({'submitted': len(mappings), 'newTaskId': mapping['newTaskId']}), flush=True)
        evidence = Path('/opt/yuyutian/mytools/secure-evidence/' + RECOVERY)
        evidence.mkdir(mode=0o700, exist_ok=True)
        journal = evidence / 'mappings.json'
        journal.write_text(json.dumps(mappings, indent=2))
        journal.chmod(0o600)
        print(json.dumps({'recovery': RECOVERY, 'trackedRequests': len(mappings)}), flush=True)
    finally:
        for connection in (task, download, automation):
            connection.close()


if __name__ == '__main__':
    main()
