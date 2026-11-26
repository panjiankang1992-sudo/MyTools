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

    import sqlite3,time,urllib.request
    pid=subprocess.check_output(['systemctl','show','mytools-download-ingestion-service','-p','MainPID','--value'],text=True).strip()
    env=dict(x.split('=',1) for x in Path('/proc/'+pid+'/environ').read_text().split('\0') if '=' in x)
    dbpath=Path(env['DOWNLOAD_APP_UPLOAD_DIR'])/'uploads.sqlite3'
    db=sqlite3.connect('file:'+str(dbpath)+'?mode=ro',uri=True);db.row_factory=sqlite3.Row
    original=['1a8130ad-dbb3-4983-81ee-bf6631635e6a','2c94def8-89f1-427c-bc89-7100739f93b8']
    rows=db.execute('SELECT id,owner,request_id FROM entry WHERE request_id IN (?,?)',original).fetchall()
    owner=rows[0]['owner'] if rows else db.execute('SELECT owner FROM entry ORDER BY created DESC LIMIT 1').fetchone()['owner']
    req=urllib.request.Request('http://127.0.0.1:23220/internal/v1/app-download-tools/history?ownerId='+str(owner),headers={'Authorization':'Bearer '+env['DOWNLOAD_INTERNAL_TOKEN'],'X-Owner-Id':str(owner),'X-Resource-Username':'owner_'+str(owner)})
    start=time.monotonic()
    with urllib.request.urlopen(req,timeout=40) as response:result=json.load(response)
    print(json.dumps({'historySeconds':round(time.monotonic()-start,3),'responseType':type(result).__name__,'entriesBoundToSuccessfulRecovery':len(rows)}))
    print(json.dumps({'historyItems':len(result) if isinstance(result,list) else None,'recoveredHistoryStatus':[r.get('status') for r in result if isinstance(r,dict) and r.get('requestId') in original]}))
    for entry in rows:
        req=urllib.request.Request('http://127.0.0.1:23220/internal/v1/app-download-tools/messages/'+entry['id'],headers={'Authorization':'Bearer '+env['DOWNLOAD_INTERNAL_TOKEN'],'X-Owner-Id':str(entry['owner']),'X-Resource-Username':'owner_'+str(entry['owner'])})
        with urllib.request.urlopen(req,timeout=30) as response:detail=json.load(response)
        assert detail['status']=='SUCCEEDED'
        print(json.dumps({'detailStatus':detail['status'],'files':detail['result']['itemCount']}))
    assert len(rows)==2
    db.close()
main()
