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

    from urllib.request import Request,urlopen
    def api(path,body=None):
        req=Request('http://127.0.0.1:23220'+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+environment['DOWNLOAD_INTERNAL_TOKEN'],'Content-Type':'application/json'})
        with urlopen(req,timeout=40) as response:return json.load(response)
    conn=connect('mytools_download','DOWNLOAD')
    ids=['f6d8e8a0-8fad-4c31-b5d3-96f8eb747f21','15e8b709-0f89-4443-aa02-cf8b26612917']
    mappings=[]
    with conn.cursor() as c:
        c.execute("SELECT id FROM download_request WHERE created_at >= %s AND status IN ('RUNNING','PLANNING')",('2026-10-27 16:00:00',))
        for row in c.fetchall():
            result=api('/api/v1/download-requests/'+row['id']);print(json.dumps({'reconciled':row['id'],'status':result['status']}))
        for identifier in ids:
            c.execute('SELECT * FROM download_request WHERE id=%s',(identifier,));old=c.fetchone()
            params=old['parameters_json'];params=json.loads(params) if isinstance(params,str) else params
            key='app-x-identity-recovery-20261104-v2:'+identifier
            result=api('/api/v1/download-requests',{'ownerId':old['owner_id'],'idempotencyKey':key,'sourceType':'APP_TOOL','sourceKey':key,'requestKind':old['request_kind'],'parameters':params})
            mapping={'oldRequestId':identifier,'newRequestId':result['id'],'newTaskId':result['task_instance_id']};mappings.append(mapping);print(json.dumps(mapping))
    evidence=Path('/opt/yuyutian/mytools/secure-evidence/app-x-identity-recovery-20261104-v2');evidence.mkdir(mode=0o700,exist_ok=True)
    target=evidence/'mappings.json';target.write_text(json.dumps(mappings));target.chmod(0o600)
    conn.close()
main()
