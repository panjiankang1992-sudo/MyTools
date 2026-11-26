"""可恢复的 GPU 验收窗口；状态持久化，任何异常都恢复原容量。"""
import contextlib
import fcntl
import json
from pathlib import Path
import subprocess
import time
import threading


def write_state(path, state):
    """原子写入不含凭证的恢复状态。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n')
    temporary.chmod(0o600)
    temporary.replace(path)


def active_competitors(deploy, definitions):
    """用新事务读取 GPU 竞争任务，避免可重复读看不到新入队任务。"""
    ids = [row['id'] for row in definitions]
    if not ids:
        raise RuntimeError('gpu_definitions_missing')
    with deploy.task_connection() as db, db.cursor() as cursor:
        cursor.execute('SELECT COUNT(*) FROM task_instance WHERE task_definition_id IN ('
                       + ','.join(['%s'] * len(ids)) + ') '
                       "AND status IN ('RUNNING','CANCELLING','QUEUED')", ids)
        return cursor.fetchone()[0]


def recover(deploy, path):
    """取消本窗口遗留作业、等其终态，然后恢复图片服务和调度容量。"""
    path = Path(path)
    if not path.exists():
        return
    state = json.loads(path.read_text())
    if state.get('restored'):
        return
    job_id = state.get('jobId')
    if job_id:
        deploy.request(23341, '/internal/v1/videos/jobs/' + job_id + '/cancel', 'POST', {})
        result = deploy.wait_job(job_id, timeout=180)
        if result['status'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'PARTIAL_SUCCESS'):
            raise RuntimeError('acceptance_job_not_terminal')
    if state.get('imageWasActive'):
        deploy.run(['systemctl', 'start', 'mytools-image-comfy'])
    with deploy.task_connection() as db, db.cursor() as cursor:
        for row in state['definitions']:
            cursor.execute('UPDATE task_definition SET max_concurrency=%s WHERE id=%s AND max_concurrency=0',
                           (row['capacity'], row['id']))
        db.commit()
    state.update(restored=True, restoredAt=time.time())
    write_state(path, state)


def sample_resources(path, stop):
    """记录整个窗口的显存与主机内存，累计峰值不能误写成单次峰值。"""
    with Path(path).open('a') as output:
        while not stop.is_set():
            try:
                gpu = subprocess.run(['nvidia-smi', '--query-gpu=memory.used,utilization.gpu',
                                      '--format=csv,noheader,nounits'], capture_output=True, text=True,
                                     timeout=5, check=True).stdout.strip()
                memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
                row = {'time': time.time(), 'gpu': gpu,
                       'availableKiB': int(memory['MemAvailable'].split()[0]),
                       'pressure': Path('/proc/pressure/memory').read_text().strip()}
            except Exception as error:
                row = {'time': time.time(), 'measurementError': type(error).__name__}
            output.write(json.dumps(row) + '\n')
            output.flush()
            stop.wait(5)


@contextlib.contextmanager
def window(deploy, path):
    """拒绝繁忙窗口；数据库暂停提交和推理均置于恢复保护内。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = deploy.ROOT / 'runtime/video-generation/acceptance.lock'
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if path.exists():
            raise RuntimeError('acceptance_output_already_used')
        state = None
        try:
            with deploy.task_connection() as db, db.cursor() as cursor:
                cursor.execute('SELECT DISTINCT td.id,td.name,td.max_concurrency FROM task_definition td '
                               'JOIN task_step_definition ts ON ts.task_definition_id=td.id '
                               'WHERE ts.script_package IN (%s,%s) FOR UPDATE', deploy.GPU_PACKAGES)
                definitions = [{'id': row[0], 'name': row[1], 'capacity': row[2]} for row in cursor.fetchall()]
                if not definitions or any(row['capacity'] < 0 for row in definitions):
                    raise RuntimeError('gpu_capacity_unexpected')
                if active_competitors(deploy, definitions):
                    raise RuntimeError('production_gpu_tasks_active')
                state = {'definitions': definitions, 'restored': False, 'startedAt': time.time(),
                         'imageWasActive': subprocess.run(['systemctl', 'is-active', '--quiet', 'mytools-image-comfy'],
                                                         check=False).returncode == 0}
                write_state(path, state)
                for row in definitions:
                    cursor.execute('UPDATE task_definition SET max_concurrency=0 WHERE id=%s', (row['id'],))
                db.commit()
            # 提交后重新检查抢先派发的任务，不能靠暂停前的快照证明空闲。
            if active_competitors(deploy, definitions):
                raise RuntimeError('production_gpu_tasks_raced')
            deploy.run(['systemctl', 'stop', 'mytools-image-comfy'])
            stop = threading.Event()
            sampler = threading.Thread(target=sample_resources, args=(path.parent / 'resources.jsonl', stop), daemon=True)
            sampler.start()
            try:
                yield state
            finally:
                stop.set()
                sampler.join(timeout=10)
        finally:
            if state is not None:
                recover(deploy, path)


if __name__ == '__main__':
    import sys
    import deploy_video_generation
    recover(deploy_video_generation, sys.argv[1])
