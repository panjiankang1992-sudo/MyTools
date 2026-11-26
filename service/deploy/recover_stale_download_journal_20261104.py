from pathlib import Path
import subprocess,json,re
import pymysql
def main():
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

    import sqlite3,os
    unit='mytools-task-executor-service'
    subprocess.run(['systemctl','stop',unit],check=True)
    try:
        dbpath='/opt/yuyutian/mytools/runtime/tasks-report-journal/execution-report.db'
        db=sqlite3.connect('file:'+dbpath+'?mode=ro',uri=True)
        t=connect('mytools_task','TASK')
        executions={}
        reports=[]
        for row in db.execute("SELECT id,payload_json FROM journal_report WHERE state IN ('PENDING','DIAGNOSTIC')"):
            p=json.loads(row[1]);executions[p['task']['executionId']]=None;reports.append(row[0])
        for row in db.execute("SELECT execution_id FROM journal_execution WHERE state='CLAIMED'"):
            executions[row[0]]=None
        with t.cursor() as c:
            for identifier in executions:
                c.execute('SELECT status,lease_lost_at FROM task_execution WHERE id=%s',(identifier,));e=c.fetchone()
                assert e and e['status']=='TIMED_OUT' and e['lease_lost_at'] is not None,'not_a_proven_lost_lease'
                executions[identifier]=e['status']
        db.close();t.close()
        payload=json.dumps({'executions':executions,'reports':reports})
        child_code='import sqlite3,json,sys,os\nfrom pathlib import Path\nfrom datetime import datetime,UTC\nos.umask(0o077)\nplan=json.load(sys.stdin)\nroot=Path(\'/opt/yuyutian/mytools/runtime/tasks-report-journal\')\nevidence=root/\'manual-stale-lease-recovery-20261104-v1\'\nif evidence.exists():\n    assert not plan[\'reports\'] and not plan[\'executions\'], \'new_stale_reports_require_separate_audit\'\n    assert (evidence/\'before.db\').is_file() and (evidence/\'audit.json\').is_file()\n    print(json.dumps({\'alreadyRecovered\':True,\'backupRetained\':True}))\n    sys.exit(0)\nevidence.mkdir(mode=0o700,exist_ok=False)\ndb=sqlite3.connect(root/\'execution-report.db\')\nbackup=sqlite3.connect(evidence/\'before.db\');db.backup(backup);backup.close()\n(evidence/\'audit.json\').write_text(json.dumps(plan))\nwith db:\n    db.execute(\'CREATE TABLE IF NOT EXISTS manual_recovery_audit (id TEXT PRIMARY KEY, evidence_path TEXT NOT NULL, created_at TEXT NOT NULL)\')\n    db.execute(\'INSERT INTO manual_recovery_audit VALUES (?,?,?)\',(\'stale-leases-20261104-v1\',str(evidence),datetime.now(UTC).isoformat()))\n    for identifier in plan[\'reports\']:\n        db.execute("DELETE FROM journal_report WHERE id=? AND state IN (\'PENDING\',\'DIAGNOSTIC\')",(identifier,))\n    for identifier in plan[\'executions\']:\n        db.execute("DELETE FROM journal_execution WHERE execution_id=? AND state=\'CLAIMED\'",(identifier,))\ndb.close()\nprint(json.dumps({\'archivedStaleReports\':len(plan[\'reports\']),\'archivedLostExecutions\':len(plan[\'executions\']),\'backupRetained\':True}))\n'
        subprocess.run(['runuser','-u','mytools','--','/opt/yuyutian/mytools/releases/current/venv/bin/python','-c',child_code],input=payload,text=True,check=True)
    finally:
        subprocess.run(['systemctl','start',unit],check=True)
main()
