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

    from collections import defaultdict,Counter
    import statistics
    from datetime import datetime
    def q(db,sql,args=()):
        with db.cursor() as c:c.execute(sql,args);return c.fetchall()
    def stats(v):
        v=sorted(v);return {'n':len(v),'p50s':round(statistics.median(v),3) if v else None,'maxs':round(max(v),3) if v else None,'sumSeconds':round(sum(v),3)}
    t=connect('mytools_task','TASK')
    rows=q(t,"SELECT ti.task_name,ti.created_at,ti.started_at,ti.status,e.started_at execution_start,e.finished_at FROM task_instance ti LEFT JOIN task_execution e ON e.task_instance_id=ti.id WHERE ti.created_at >= %s AND ti.task_name IN ('message_download_attachment','message_reconcile_attachment_download')",('2026-10-28',))
    queues=defaultdict(list);runtime=defaultdict(list)
    for r in rows:
        if r['started_at']:queues[r['task_name']].append((r['started_at']-r['created_at']).total_seconds())
        if r['execution_start'] and r['finished_at']:runtime[r['task_name']].append((r['finished_at']-r['execution_start']).total_seconds())
    print('queue',{k:stats(v) for k,v in queues.items()});print('runtime',{k:stats(v) for k,v in runtime.items()})
    print('wrapper_limits',q(t,"SELECT td.name,td.max_concurrency,ec.max_concurrent_tasks cluster_limit FROM task_definition td LEFT JOIN execution_cluster ec ON ec.id=td.cluster_id WHERE td.name IN ('message_download_attachment','message_reconcile_attachment_download')"))
    for stamp in ('2026-11-03 01:55:00','2026-11-03 02:10:00','2026-11-03 02:30:00'):
        print(stamp,q(t,"SELECT ti.task_name,COUNT(*) n FROM task_execution e JOIN task_instance ti ON ti.id=e.task_instance_id WHERE ti.created_at >= %s AND e.started_at <= %s AND (e.finished_at IS NULL OR e.finished_at > %s) GROUP BY ti.task_name",('2026-10-28',stamp,stamp)))
    t.close()
main()
