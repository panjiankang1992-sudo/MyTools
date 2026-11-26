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

    mappings=json.loads(Path('/opt/yuyutian/mytools/secure-evidence/app-x-identity-recovery-20261104-v2/mappings.json').read_text())
    t=connect('mytools_task','TASK');d=connect('mytools_download','DOWNLOAD')
    for m in mappings:
        with t.cursor() as c:
            c.execute('SELECT status FROM task_instance WHERE id=%s',(m['newTaskId'],));m['task']=c.fetchone()
            c.execute('SELECT task_name,status,COUNT(*) n FROM task_instance WHERE parent_task_instance_id=%s GROUP BY task_name,status',(m['newTaskId'],));m['children']=c.fetchall()
        with d.cursor() as c:
            c.execute('SELECT status,COUNT(*) n,COALESCE(SUM(size_bytes),0) bytes FROM download_item WHERE download_request_id=%s GROUP BY status',(m['newRequestId'],));m['files']=c.fetchall()
        print(json.dumps(m,default=str))
    t.close();d.close()
main()
