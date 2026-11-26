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
    from datetime import timedelta,datetime
    import statistics,math
    def query(conn,sql,args=()):
        with conn.cursor() as c:c.execute(sql,args);return c.fetchall()
    def stats(values):
        values=sorted(float(v) for v in values if v is not None and v>=0)
        if not values:return {'n':0}
        def pct(p):
            x=(len(values)-1)*p;lo=int(x);hi=min(lo+1,len(values)-1)
            return round(values[lo]+(values[hi]-values[lo])*(x-lo),3)
        return {'n':len(values),'p50s':pct(.5),'p90s':pct(.9),'p95s':pct(.95),'maxs':round(max(values),3),'sumSeconds':round(sum(values),3)}
    t=connect('mytools_task','TASK');d=connect('mytools_download','DOWNLOAD')
    tasks=query(t,"SELECT id,parent_task_instance_id,task_name,status,created_at,started_at,updated_at,dispatch_attempts FROM task_instance WHERE created_at >= %s AND task_name LIKE 'download%%'",('2026-10-28',))
    steps=query(t,"SELECT ti.id task_id,ti.task_name,se.task_execution_id execution_id,se.attempt,se.status,se.error_code,se.started_at,se.finished_at,sd.name step,sd.script_package FROM step_execution se JOIN task_execution e ON e.id=se.task_execution_id JOIN task_instance ti ON ti.id=e.task_instance_id JOIN task_step_definition sd ON sd.id=se.step_definition_id WHERE ti.created_at >= %s AND ti.task_name LIKE 'download%%'",('2026-10-28',))
    executions=query(t,"SELECT e.id,e.task_instance_id,e.status,e.started_at,e.finished_at FROM task_execution e JOIN task_instance ti ON ti.id=e.task_instance_id WHERE ti.created_at >= %s AND ti.task_name LIKE 'download%%'",('2026-10-28',))
    requests=query(d,'SELECT id,source_type,request_kind,status,task_instance_id,created_at FROM download_request WHERE created_at >= %s',('2026-10-27 16:00:00',))
    files=query(d,'SELECT i.download_request_id,i.status,i.size_bytes,i.created_at FROM download_item i JOIN download_request r ON r.id=i.download_request_id WHERE r.created_at >= %s',('2026-10-27 16:00:00',))
    by_id={r['id']:r for r in tasks};by_parent=defaultdict(list)
    for r in tasks:by_parent[r['parent_task_instance_id']].append(r)
    done=defaultdict(list)
    for e in executions:
        if e['status']=='SUCCEEDED' and e['finished_at']:done[e['task_instance_id']].append(e['finished_at'])
    stages=defaultdict(list);phase_stages=defaultdict(lambda:defaultdict(list));failures=Counter()
    execution_start={e['id']:e['started_at'] for e in executions}
    previous=dict(execution_start)
    raw_equal=0
    for r in sorted(steps,key=lambda r:(r['execution_id'],r['finished_at'] or datetime.min)):
        raw_equal+=int(r['started_at']==r['finished_at'])
        before=previous.get(r['execution_id'])
        finish=r['finished_at']
        if finish:previous[r['execution_id']]=finish
        if r['status']!='SUCCEEDED':failures[(r['step'],r['error_code'])]+=1;continue
        if not before or not finish:continue
        seconds=(finish-before).total_seconds();stages[r['step']].append(seconds)
        phase='gpu' if finish>=datetime(2026,11,4,10,56,58) else 'cpu_incident' if finish>=datetime(2026,11,4,9,0) and finish<datetime(2026,11,4,10,52,28) else 'historical'
        phase_stages[phase][r['step']].append(seconds)
    queue=defaultdict(list);runtimes=defaultdict(list);parse_startup=defaultdict(list)
    for r in tasks:
        if r['started_at']:queue[r['task_name']].append((r['started_at']-r['created_at']).total_seconds())
        if done[r['id']] and r['started_at']:runtimes[r['task_name']].append((max(done[r['id']])-r['started_at']).total_seconds())
        children=by_parent[r['id']]
        if r['started_at'] and children:
            parse_startup[r['task_name']].append((min(c['created_at'] for c in children)-r['started_at']).total_seconds())
    request_stats=defaultdict(list);file_by_request=defaultdict(list);offsets=[];request_detail=[];core_ready=defaultdict(list);tail=defaultdict(list);sizes=defaultdict(list)
    for f in files:file_by_request[f['download_request_id']].append(f)
    for r in requests:
        task=by_id.get(r['task_instance_id']);received=r['created_at']+timedelta(hours=8)
        if task:offsets.append((task['created_at']-r['created_at']).total_seconds())
        if task and done[task['id']]:
            elapsed=(max(done[task['id']])-received).total_seconds()
            request_stats[r['request_kind']].append(elapsed)
            if file_by_request[r['id']]:
                last_file=max(f['created_at'] for f in file_by_request[r['id']])+timedelta(hours=8)
                gap=(max(done[task['id']])-last_file).total_seconds()
                if task['started_at']:core_ready[r['request_kind']].append((last_file-task['started_at']).total_seconds())
                tail[r['request_kind']].append(gap)
                sizes[r['request_kind']].append(sum(f['size_bytes'] or 0 for f in file_by_request[r['id']]))
            else:gap=None
            request_detail.append({'id':r['id'],'kind':r['request_kind'],'source':r['source_type'],'elapsedSeconds':round(elapsed,3),'files':len(file_by_request[r['id']]),'bytes':sum(f['size_bytes'] or 0 for f in file_by_request[r['id']]),'afterLastFileSeconds':None if gap is None else round(gap,3),'createdBeijing':str(received),'finishedBeijing':str(max(done[task['id']]))})
    definitions=query(t,"SELECT td.name,td.max_concurrency,ec.name cluster_name,ec.max_concurrent_tasks cluster_limit FROM task_definition td LEFT JOIN execution_cluster ec ON ec.id=td.cluster_id WHERE td.name LIKE 'download%%'")
    step_config=query(t,"SELECT td.name task,sd.name step,sd.script_package,sd.script_version,sd.sequence_number,sd.max_attempts,sd.failure_policy FROM task_step_definition sd JOIN task_definition td ON td.id=sd.task_definition_id WHERE td.name IN ('download_http_asset','download_message_attachment','download_message_url_batch','download_x_post','download_x_user') ORDER BY td.name,sd.sequence_number")
    output={'windowBeijing':'2026-10-28 to current','requestCount':len(requests),'requestStatus':dict(Counter(r['status'] for r in requests)),'taskCount':len(tasks),'stepCount':len(steps),'stepTimingMethod':'Sequential report interval estimate including process startup, retry gaps and report overhead; first step starts at execution claim','zeroRecordedStepDurations':raw_equal,'requestToTaskStoredClockOffset':stats(offsets),'requestElapsed':{k:stats(v) for k,v in request_stats.items()},'lastFileReadyAfterTaskStart':{k:stats(v) for k,v in core_ready.items()},'taskCompletionAfterLastFileReady':{k:stats(v) for k,v in tail.items()},'requestBytes':{k:{'n':stats(v)['n'],'p50Bytes':stats(v).get('p50s'),'p95Bytes':stats(v).get('p95s'),'maxBytes':stats(v).get('maxs'),'totalBytes':stats(v).get('sumSeconds')} for k,v in sizes.items()},'queueByTask':{k:stats(v) for k,v in queue.items()},'taskElapsedIncludingChildren':{k:stats(v) for k,v in runtimes.items()},'firstChildStartupIncludingParsing':{k:stats(v) for k,v in parse_startup.items()},'stepDurations':{k:stats(v) for k,v in stages.items()},'stepDurationsByPhase':{p:{k:stats(v) for k,v in v.items()} for p,v in phase_stages.items()},'stepErrors':[{'step':k[0],'code':k[1],'n':v} for k,v in failures.items()],'slowestRequests':sorted(request_detail,key=lambda r:r['elapsedSeconds'],reverse=True)[:15],'definitions':definitions,'steps':step_config}
    print(json.dumps(output,default=str))
    t.close();d.close()
main()
