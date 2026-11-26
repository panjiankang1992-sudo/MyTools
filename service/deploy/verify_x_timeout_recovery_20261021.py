#!/usr/bin/env python3
"""只读核验本次重跑、真实文件结果、原失败记录及机器人完成事件。"""
from pathlib import Path
import json
import subprocess

import pymysql

RECOVERY = 'x-dispatch-timeouts-20261021-v1'


def main():
    """每次获取权威状态，不以旧失败通知或新任务受理代替完成证据。"""
    mappings = json.loads(Path('/opt/yuyutian/mytools/secure-evidence/' + RECOVERY + '/mappings.json').read_text())
    pid = subprocess.check_output(['systemctl', 'show', 'mytools-task-scheduler-service',
                                   '-p', 'MainPID', '--value'], text=True).strip()
    environment = dict(value.split('=', 1) for value in
                       Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)

    def connect(database, prefix):
        """各连接使用服务自身数据库账号，核验仅执行 SELECT。"""
        user_key = prefix + '_DB_' + ('USERNAME' if prefix == 'TASK' else 'USER')
        return pymysql.connect(host='127.0.0.1', user=environment[user_key],
                               password=environment[prefix + '_DB_PASSWORD'], database=database,
                               cursorclass=pymysql.cursors.DictCursor)

    task = connect('mytools_task', 'TASK')
    download = connect('mytools_download', 'DOWNLOAD')
    automation = connect('mytools_message_automation', 'MESSAGE_AUTOMATION')
    states = {}
    run_states = {}
    delivered = files = total_bytes = original_timeouts = expired_queue_fields = 0
    failures = []
    try:
        for mapping in mappings:
            with task.cursor() as cursor:
                cursor.execute('SELECT status, dispatch_deadline_at FROM task_instance WHERE id = %s',
                               (mapping['newTaskId'],))
                current = cursor.fetchone()
                states[current['status']] = states.get(current['status'], 0) + 1
                expired_queue_fields += int(current['dispatch_deadline_at'] is not None)
                if current['status'] in ('FAILED', 'TIMED_OUT', 'CANCELLED'):
                    failures.append(mapping['newTaskId'])
                cursor.execute('SELECT status FROM task_instance WHERE id = %s', (mapping['originalTaskId'],))
                original_timeouts += int(cursor.fetchone()['status'] == 'TIMED_OUT')
            with download.cursor() as cursor:
                cursor.execute('SELECT COUNT(*) AS n, COALESCE(SUM(size_bytes), 0) AS bytes '
                               "FROM download_item WHERE download_request_id = %s AND status = 'COMPLETED' "
                               "AND storage_uri IS NOT NULL AND asset_id IS NOT NULL AND size_bytes > 0",
                               (mapping['newDownloadRequestId'],))
                result = cursor.fetchone()
                files += result['n']
                total_bytes += int(result['bytes'])
            with automation.cursor() as cursor:
                cursor.execute('SELECT status FROM automation_run WHERE id = %s', (mapping['runId'],))
                status = cursor.fetchone()['status']
                run_states[status] = run_states.get(status, 0) + 1
                cursor.execute("""SELECT COUNT(*) AS n FROM automation_outbox completed
                    WHERE completed.aggregate_id = %s AND completed.event_type = %s
                      AND completed.published_at IS NOT NULL AND completed.dead_at IS NULL
                      AND completed.created_at >= (SELECT created_at FROM automation_outbox audit
                        WHERE audit.aggregate_id = %s AND audit.event_type = %s ORDER BY created_at DESC LIMIT 1)""",
                               (mapping['runId'], 'AutomationRunCompleted', mapping['runId'], 'AutomationRunRecoveryRecorded'))
                delivered += int(cursor.fetchone()['n'] > 0)
        print(json.dumps({'recovery': RECOVERY, 'tasks': states, 'automationRuns': run_states,
                          'savedFiles': files, 'savedBytes': total_bytes, 'newCompletionMessagesDelivered': delivered,
                          'originalTimeoutRecordsPreserved': original_timeouts, 'queueDeadlineFields': expired_queue_fields,
                          'failedTaskIds': failures}), flush=True)
    finally:
        for connection in (task, download, automation):
            connection.close()


if __name__ == '__main__':
    main()
