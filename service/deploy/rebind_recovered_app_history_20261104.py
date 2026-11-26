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

    mappings=json.loads(Path('/opt/yuyutian/mytools/secure-evidence/app-x-media-recovery-20261104-v4/mappings.json').read_text())
    pid=subprocess.check_output(['systemctl','show','mytools-download-ingestion-service','-p','MainPID','--value'],text=True).strip()
    env=dict(x.split('=',1) for x in Path('/proc/'+pid+'/environ').read_text().split('\0') if '=' in x)
    conn=connect('mytools_download','DOWNLOAD');entries=[]
    with conn.cursor() as c:
        for mapping in mappings:
            c.execute('SELECT owner_id,source_type,status,parameters_json FROM download_request WHERE id=%s',(mapping['oldRequestId'],));old=c.fetchone()
            c.execute('SELECT owner_id,source_type,status,parameters_json FROM download_request WHERE id=%s',(mapping['newRequestId'],));new=c.fetchone()
            assert old['owner_id']==new['owner_id'] and new['status']=='SUCCEEDED'
            assert old['source_type']==new['source_type']=='APP_TOOL'
            assert json.loads(old['parameters_json'])==json.loads(new['parameters_json'])
            entries.append({'owner':old['owner_id'],'old':mapping['oldRequestId'],'new':mapping['newRequestId']})
    conn.close()
    plan={'database':str(Path(env['DOWNLOAD_APP_UPLOAD_DIR'])/'uploads.sqlite3'),'entries':entries}
    subprocess.run(['runuser','-u','mytools','--','/opt/yuyutian/mytools/releases/current/venv/bin/python','-c',"from pathlib import Path\nimport sqlite3,json,sys,os\nfrom datetime import datetime,UTC\nos.umask(0o077)\nplan=json.load(sys.stdin);path=Path(plan['database']);db=sqlite3.connect(path)\nevidence=path.parent/'manual-ui-recovery-20261104-v1'\nevidence.mkdir(mode=0o700,exist_ok=True)\nif not (evidence/'before.db').exists():\n    backup=sqlite3.connect(evidence/'before.db');db.backup(backup);backup.close()\nchanged=0\nwith db:\n    db.execute('CREATE TABLE IF NOT EXISTS recovery_audit(id TEXT PRIMARY KEY,entry_id TEXT NOT NULL,old_request_id TEXT NOT NULL,new_request_id TEXT NOT NULL,created_at TEXT NOT NULL)')\n    for item in plan['entries']:\n        audit='app-ui-recovery-20261104:'+item['old']\n        prior=db.execute('SELECT entry_id,new_request_id FROM recovery_audit WHERE id=?',(audit,)).fetchone()\n        if prior:\n            assert prior[1]==item['new'];continue\n        rows=db.execute('SELECT id FROM entry WHERE owner=? AND request_id=?',(item['owner'],item['old'])).fetchall()\n        assert len(rows)==1,'unexpected_entry_binding'\n        entry=rows[0][0]\n        changed+=db.execute('UPDATE entry SET request_id=? WHERE id=? AND owner=? AND request_id=?',(item['new'],entry,item['owner'],item['old'])).rowcount\n        db.execute('INSERT INTO recovery_audit VALUES(?,?,?,?,?)',(audit,entry,item['old'],item['new'],datetime.now(UTC).isoformat()))\nprint(json.dumps({'recoveredAppHistoryEntries':changed}))\ndb.close()\n"],input=json.dumps(plan),text=True,check=True)
main()
