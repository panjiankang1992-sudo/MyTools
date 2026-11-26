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
    mappings=json.loads(Path('/opt/yuyutian/mytools/secure-evidence/app-x-media-recovery-20261104-v4/mappings.json').read_text())
    d=connect('mytools_download','DOWNLOAD');t=connect('mytools_task','TASK')
    total_files=total_bytes=0
    summaries=[]
    for m in mappings:
        result=api('/api/v1/download-requests/'+m['newRequestId'])
        assert result['status']=='SUCCEEDED'
        with d.cursor() as c:
            c.execute("SELECT COUNT(*) n,COALESCE(SUM(size_bytes),0) bytes,SUM(CASE WHEN status='COMPLETED' AND size_bytes>0 AND storage_uri IS NOT NULL AND asset_id IS NOT NULL AND LENGTH(content_sha256)=64 THEN 1 ELSE 0 END) verified FROM download_item WHERE download_request_id=%s",(m['newRequestId'],));files=c.fetchone()
            assert files['n']==files['verified'] and files['n']>0
        with t.cursor() as c:
            c.execute('SELECT status,dispatch_deadline_at FROM task_instance WHERE id=%s',(m['newTaskId'],));task=c.fetchone()
            assert task['status']=='SUCCEEDED' and task['dispatch_deadline_at'] is None
        total_files+=files['n'];total_bytes+=int(files['bytes'])
        summaries.append({'requestId':m['newRequestId'],'status':result['status'],'verifiedFiles':files['n']})
    assert total_files==102
    print(json.dumps({'recoveredRequests':summaries,'totalVerifiedFiles':total_files,'totalBytes':total_bytes}))
    d.close();t.close()
main()
