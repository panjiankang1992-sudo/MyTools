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

    import statistics
    from collections import defaultdict,Counter
    from datetime import timedelta
    def q(db,sql,args=()):
        with db.cursor() as c:c.execute(sql,args);return c.fetchall()
    def stat(v):
        v=sorted(v)
        return {'n':len(v),'p50':round(statistics.median(v),3) if v else None,'max':round(max(v),3) if v else None}
    t=connect('mytools_task','TASK');d=connect('mytools_download','DOWNLOAD');m=connect('mytools_messaging','MESSAGING')
    jobs=q(m,"SELECT j.download_request_id,j.created_at,m.created_at message_created_at,j.updated_at job_updated_at,m.channel_type,m.received_at FROM attachment_download_job j JOIN inbound_message m ON m.id=j.inbound_message_id WHERE j.created_at >= %s",('2026-10-28',))
    reqs={r['id']:r for r in q(d,'SELECT id,created_at,task_instance_id FROM download_request WHERE created_at >= %s',('2026-10-27 16:00:00',))}
    ingress=[];channels=Counter();receive_to_persist=[];persist_to_job=[];job_to_request=[];user_wait=[]
    finishes={r['task_instance_id']:r['finished_at'] for r in q(t,"SELECT e.task_instance_id,MAX(e.finished_at) finished_at FROM task_execution e JOIN task_instance ti ON ti.id=e.task_instance_id WHERE ti.created_at >= %s AND ti.task_name='download_message_attachment' AND e.status='SUCCEEDED' GROUP BY e.task_instance_id",('2026-10-28',))}
    for j in jobs:
        if j['download_request_id'] in reqs:
            channels[j['channel_type']]+=1
            end=finishes.get(reqs[j['download_request_id']]['task_instance_id'])
            if end:user_wait.append((end-j['received_at']).total_seconds())
            ingress.append((reqs[j['download_request_id']]['created_at']+timedelta(hours=8)-j['received_at']).total_seconds())
            receive_to_persist.append((j['message_created_at']-j['received_at']).total_seconds())
            persist_to_job.append((j['created_at']-j['message_created_at']).total_seconds())
            job_to_request.append((reqs[j['download_request_id']]['created_at']+timedelta(hours=8)-j['created_at']).total_seconds())
    print('message_received_to_download_task_complete',stat(user_wait));print('attachment_channels',dict(channels));print('message_to_request_seconds',stat(ingress));print('receive_to_message_persist',stat(receive_to_persist));print('message_to_job',stat(persist_to_job));print('job_to_request',stat(job_to_request))
    errors=q(t,"SELECT sd.name,se.error_code,se.error_message FROM step_execution se JOIN task_execution e ON e.id=se.task_execution_id JOIN task_instance ti ON ti.id=e.task_instance_id JOIN task_step_definition sd ON sd.id=se.step_definition_id WHERE ti.created_at >= %s AND ti.task_name LIKE 'download%%' AND se.status='FAILED'",('2026-10-28',))
    buckets=Counter()
    for e in errors:
        text=e['error_message'] or ''
        reason=next((x for x in ('GPU_BUSY','GPU_COMFY_BUSY','GPU_MEMORY_UNAVAILABLE','media source does not exist','media source is missing','KeyError','ffmpeg','HTTP Error 500','timed out') if x in text),e['error_code'])
        buckets[(e['name'],reason)]+=1
    print('failure_reasons',[{'step':k[0],'reason':k[1],'n':v} for k,v in buckets.items()])
    print('nodes',q(t,'SELECT name,max_concurrent_tasks,running_tasks,enabled,status FROM executor_node'))
    pid=subprocess.check_output(['systemctl','show','mytools-task-executor-service','-p','MainPID','--value'],text=True).strip()
    env=dict(x.split('=',1) for x in Path('/proc/'+pid+'/environ').read_text().split('\0') if '=' in x)
    print('executor_safe_limits',{k:env.get(k) for k in ('TASK_EXECUTOR_MAX_CONCURRENT_TASKS','TASK_EXECUTOR_RESERVED_CHILD_TASK_SLOTS','TASK_EXECUTOR_POLL_SECONDS')})
    for db in (t,d,m):db.close()
main()
