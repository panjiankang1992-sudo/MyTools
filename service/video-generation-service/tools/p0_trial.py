#!/usr/bin/env python3
"""受控维护窗口验证视频工作流；出现生产任务或资源越界即停止实验并恢复调度。

一个维护窗口内顺序执行多个工作流，避免为每个模式单独中断图片服务。窗口内先暂停所有含
GPU 步骤的任务定义并等待在途任务排空，再取得共享 GPU 锁；任一次运行失败即停止后续运行，
由人工另开窗口，不自动降参数覆盖已有记录。
"""
import fcntl
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

ROOT = Path('/opt/yuyutian/mytools/runtime/video-generation')
COMFY = ROOT / 'runtime-v1/ComfyUI'
STATE = ROOT / 'evidence/maintenance.json'
UNIT = 'mytools-video-p0-runtime'
PACKAGES = ('image_generate', 'media_generate_tags')
# 实测宿主机可用内存天花板约 18.5GiB（停 image Comfy 与该服务后），方案原定 20GiB 门槛在本机不可达：
# image Comfy 的 cgroup 数字几乎全为可回收页缓存，已计入 MemAvailable。按“运行中主机保留不少于 6GiB”
# 这一不变式反推：worker MemoryMax = 启动门槛 − 6GiB 保留，超限只由本单元自身 OOM 承担。
HOST_RESERVE_MIB = 6 * 1024
WORKER_MEMORY_HIGH = '10G'
WORKER_MEMORY_MAX = '12G'
START_GATE_MIB = 18 * 1024
HOST_UNITS = ('xianyu-assistant',)
# 只放行本仓库自带的派生节点；其余自定义节点继续被 --disable-all-custom-nodes 关闭。
CUSTOM_NODES = 'wan_vace_multi_reference'
RUN_TIMEOUT_SECONDS = 1800
MAX_WINDOW_SECONDS = 4500
MAX_RUNS = 3
# 运维依赖只放在隔离运行目录：ops-python 提供 pymysql，部署助手由发布流程放到 tools。
for location in (ROOT / 'tools', ROOT / 'ops-python', Path('/tmp')):
    sys.path.insert(0, str(location))
import deploy_image_generation as deployment
from workflow_validate import validate, validate_input_files


def call(port, path, body=None):
    """仅访问固定本地服务。"""
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def command(*args, check=True):
    """所有运行时操作使用固定参数，不经过 shell。"""
    result = subprocess.run(args, capture_output=True, text=True, timeout=90, check=check)
    return result.stdout.strip()


def save(path, data):
    """原子保存恢复状态和实验结果。"""
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    temporary.chmod(0o600)
    temporary.replace(path)


def gpu_tasks(db, ids):
    """只查询包含 GPU 步骤的任务，不读取用户参数。"""
    with db.cursor() as cursor:
        placeholders = ','.join(['%s'] * len(ids))
        cursor.execute(f"SELECT status, COUNT(*) FROM task_instance WHERE task_definition_id IN ({placeholders}) "
                       "AND status IN ('RUNNING','CANCELLING','QUEUED') GROUP BY status", ids)
        return dict(cursor.fetchall())


def drain(db, ids, timeout):
    """暂停派发后等待在途任务结束；不取消其他用户任务，超时即放弃本次实验。"""
    deadline = time.monotonic() + timeout
    while gpu_tasks(db, ids):
        if time.monotonic() >= deadline:
            raise TimeoutError('PRODUCTION_GPU_TASKS_ACTIVE')
        time.sleep(5)


def snapshot():
    """保存宿主机可用资源，不读取私密配置。"""
    memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    return {'time': time.time(), 'availableMiB': int(memory['MemAvailable'].split()[0]) // 1024,
            'gpu': command('nvidia-smi', '--query-gpu=memory.used,memory.free,utilization.gpu', '--format=csv,noheader,nounits'),
            'memoryPressure': Path('/proc/pressure/memory').read_text().strip()}


def restore():
    """先终止视频进程，再恢复图片服务和原调度容量；可重复执行。"""
    if not STATE.exists():
        return
    state = json.loads(STATE.read_text())
    if state.get('restored'):
        return
    command('systemctl', 'stop', UNIT, check=False)
    if command('systemctl', 'is-active', UNIT, check=False) in ('active', 'activating', 'deactivating'):
        raise RuntimeError('VIDEO_RUNTIME_NOT_STOPPED')
    if state['imageWasActive']:
        command('systemctl', 'start', 'mytools-image-comfy')
        for _ in range(60):
            try:
                call(8189, '/queue')
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('IMAGE_RESTORE_NOT_READY')
    for name in state.get('hostUnits', []):
        command('systemctl', 'start', name)
    with deployment.task_connection() as db, db.cursor() as cursor:
        for row in state['definitions']:
            # 不覆盖窗口内由其他维护者改动的值。
            cursor.execute('UPDATE task_definition SET max_concurrency=%s WHERE id=%s AND max_concurrency=0',
                           (row['capacity'], row['id']))
        db.commit()
    state['restored'] = True
    state['restoredAt'] = time.time()
    save(STATE, state)
    print(json.dumps({'event': 'production_restored'}), flush=True)


def run_one(folder, graph, result, ids, cold):
    """提交一个工作流并轮询到结束，采集产物与资源证据。"""
    folder.mkdir(parents=True, exist_ok=True)
    graph['11']['inputs']['filename_prefix'] = folder.name + '/frame'
    save(folder / 'workflow.json', graph)
    result.update(warmStart=not cold, workflowSha256=hashlib.sha256(deserialize(graph)).hexdigest())
    save(folder / 'result.json', result)
    submitted = call(8190, '/prompt', {'prompt': graph, 'client_id': folder.name})
    result.update(status='RUNNING', promptId=submitted['prompt_id'], submittedAt=time.time())
    save(folder / 'result.json', result)
    print(json.dumps(result), flush=True)
    deadline = time.monotonic() + RUN_TIMEOUT_SECONDS
    with (folder / 'metrics.jsonl').open('w') as metrics:
        while time.monotonic() < deadline:
            measurement = snapshot()
            measurement['cgroup'] = command('systemctl', 'show', UNIT, '-p', 'MemoryCurrent', '-p', 'MemoryPeak', '-p', 'Result')
            metrics.write(json.dumps(measurement) + '\n')
            metrics.flush()
            if measurement['availableMiB'] < HOST_RESERVE_MIB:
                raise MemoryError('HOST_RAM_PRESSURE')
            with deployment.task_connection() as db:
                if gpu_tasks(db, ids):
                    raise RuntimeError('PRODUCTION_GPU_TASK_ARRIVED')
            history = call(8190, '/history/' + result['promptId'])
            if history:
                save(folder / 'history.json', history)
                item = history[result['promptId']]
                if item['status']['status_str'] != 'success':
                    raise RuntimeError('VIDEO_INFERENCE_FAILED:' + json.dumps(item['status'])[:200])
                images = item['outputs']['11']['images']
                expected = graph['6']['inputs']['length']
                if len(images) != expected:
                    raise RuntimeError('VIDEO_FRAME_COUNT_MISMATCH')
                inputs = []
                for image in images:
                    path = (COMFY / 'output' / image['subfolder'] / image['filename']).resolve()
                    if not path.is_relative_to((COMFY / 'output').resolve()):
                        raise ValueError('VIDEO_OUTPUT_PATH_INVALID')
                    inputs.append(path)
                concat = folder / 'frames.txt'
                concat.write_text(''.join(f"file '{path}'\nduration 0.0625\n" for path in inputs))
                output = folder / 'output.mp4'
                command('ffmpeg', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', str(concat),
                        '-r', '16', '-frames:v', str(expected), '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
                        '-movflags', '+faststart', str(output))
                save(folder / 'ffprobe.json', json.loads(command('ffprobe', '-v', 'error', '-show_streams',
                                                                '-show_format', '-of', 'json', str(output))))
                result.update(status='INFERENCE_PASSED_REVIEW_PENDING', output=str(output),
                              outputSha256=hashlib.sha256(output.read_bytes()).hexdigest())
                return result
            time.sleep(5)
    raise TimeoutError('VIDEO_TIMEOUT')


def deserialize(graph):
    """稳定序列化工作流图，用于记录模板哈希。"""
    return json.dumps(graph, sort_keys=True).encode()


def custom_node_hashes():
    """记录放行的自定义节点文件哈希，便于审计与回退。"""
    folder = COMFY / 'custom_nodes' / CUSTOM_NODES
    return {str(path.relative_to(folder)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(folder.rglob('*.py'))}


def probe_input(path, kind):
    """实际解码一次输入素材。

    只检查"文件存在且非空"抓不到被截断的图片：传输中断会留下尺寸正常但缺数据块的文件，
    ComfyUI 仍可能给出成功结果。这里必须真的解出一帧（图片）或探到视频流，否则拒绝开窗。
    """
    if kind == 'image':
        result = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-frames:v', '1',
                                 '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                                capture_output=True, timeout=180, check=False)
        return result.returncode == 0 and bool(result.stdout)
    result = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                             '-show_entries', 'stream=width,height', '-of', 'json', str(path)],
                            capture_output=True, text=True, timeout=60, check=False)
    if result.returncode:
        return False
    streams = json.loads(result.stdout or '{}').get('streams') or []
    return bool(streams) and int(streams[0].get('width') or 0) > 0


def open_window(paths):
    """暂停 GPU 派发并等待排空，返回 (状态, GPU 任务定义 ID 列表, GPU 锁句柄)。"""
    with deployment.task_connection() as db, db.cursor() as cursor:
        cursor.execute('SELECT DISTINCT td.id,td.name,td.max_concurrency FROM task_definition td '
                       'JOIN task_step_definition ts ON ts.task_definition_id=td.id '
                       'WHERE ts.script_package IN (%s,%s) FOR UPDATE', PACKAGES)
        definitions = [{'id': row[0], 'name': row[1], 'capacity': row[2]} for row in cursor.fetchall()]
        if not definitions or any(row['capacity'] <= 0 for row in definitions):
            raise RuntimeError('GPU_CAPACITY_UNEXPECTED')
        ids = [row['id'] for row in definitions]
        if gpu_tasks(db, ids):
            raise RuntimeError('PRODUCTION_GPU_TASKS_ACTIVE')
        state = {'definitions': definitions, 'restored': False, 'runIds': [folder.name for folder in paths],
                 'startedAt': time.time(),
                 'recoveryCommand': 'python3 tools/p0_trial.py restore',
                 'imageWasActive': command('systemctl', 'is-active', 'mytools-image-comfy', check=False) == 'active'}
        save(STATE, state)
        for row in definitions:
            cursor.execute('UPDATE task_definition SET max_concurrency=0 WHERE id=%s', (row['id'],))
        db.commit()
        # 先放开行锁再等待：含 GPU 步骤的下载任务在途时也必须排空，不能只限制图片任务。
        drain(db, ids, 900)
        if gpu_tasks(db, ids):
            raise RuntimeError('PRODUCTION_GPU_TASKS_ACTIVE')
    handle = None
    try:
        with deployment.task_connection() as db:
            if gpu_tasks(db, ids):
                raise RuntimeError('PRODUCTION_GPU_TASKS_RACED')
        handle = Path('/opt/yuyutian/mytools/runtime/image-generation/gpu.lock').open('a')
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        queue = call(8189, '/queue')
        if queue['queue_running'] or queue['queue_pending']:
            raise RuntimeError('IMAGE_QUEUE_BUSY')
        if call(11434, '/api/ps').get('models'):
            raise RuntimeError('OLLAMA_MODEL_RESIDENT')
        command('systemctl', 'stop', 'mytools-image-comfy')
        # 先落盘再停止可选服务，异常路径也能把它们恢复。
        state['hostUnits'] = [name for name in HOST_UNITS
                              if command('systemctl', 'is-active', name, check=False) == 'active']
        save(STATE, state)
        for name in state['hostUnits']:
            command('systemctl', 'stop', name)
        return state, ids, handle
    except BaseException:
        if handle:
            handle.close()
        raise


def start_unit(window, planned_runs):
    """校验资源门槛后启动隔离运行时，并把预检结果写入窗口证据。"""
    time.sleep(5)
    before = snapshot()
    before['admission'] = {'hostReserveMiB': HOST_RESERVE_MIB, 'workerMemoryHigh': WORKER_MEMORY_HIGH,
                           'workerMemoryMax': WORKER_MEMORY_MAX, 'startGateMiB': START_GATE_MIB,
                           'calibration': 'measured host ceiling ~18.5GiB; planned 20GiB gate unreachable'}
    save(window / 'host-before.json', before)
    if before['availableMiB'] < START_GATE_MIB:
        raise MemoryError('HOST_RAM_GATE_FAILED')
    if int(before['gpu'].split(',')[1]) < 14 * 1024:
        raise MemoryError('HOST_GPU_GATE_FAILED')
    command('systemctl', 'reset-failed', UNIT, check=False)
    command('systemd-run', '--unit=' + UNIT, '--uid=mytools', '--property=WorkingDirectory=' + str(COMFY),
            '--property=MemoryMax=' + WORKER_MEMORY_MAX, '--property=MemoryHigh=' + WORKER_MEMORY_HIGH,
            '--property=MemorySwapMax=0',
            '--property=RuntimeMaxSec=' + str(900 + 1900 * planned_runs),
            '--property=TimeoutStopSec=30',
            str(ROOT / 'runtime-v1/venv/bin/python'), 'main.py', '--listen', '127.0.0.1', '--port', '8190',
            '--lowvram', '--reserve-vram', '2', '--disable-pinned-memory', '--disable-all-custom-nodes',
            '--whitelist-custom-nodes', CUSTOM_NODES,
            '--disable-api-nodes', '--preview-method', 'none')
    for _ in range(90):
        try:
            call(8190, '/queue')
            break
        except Exception:
            time.sleep(1)
    else:
        raise RuntimeError('VIDEO_START_FAILED')
    return before


def main(workflow_paths):
    """在一个维护窗口内顺序执行给定工作流，并保证任何异常路径都恢复生产调度。"""
    if len(workflow_paths) > MAX_RUNS:
        raise ValueError('VIDEO_WINDOW_RUN_LIMIT')
    if STATE.exists() and not json.loads(STATE.read_text()).get('restored'):
        raise RuntimeError('PREVIOUS_MAINTENANCE_REQUIRES_RESTORE')
    window_id = 'window-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    window = ROOT / 'evidence' / window_id
    window.mkdir(parents=True)
    # 先建好每个运行的证据目录，任何提前失败也能留下可归档的记录。
    folders = [window / f'run-{index + 1:02d}' for index in range(len(workflow_paths))]
    for folder in folders:
        folder.mkdir()
    save(window / 'plan.json', {'runs': [{'index': index + 1, 'workflow': str(path)}
                                         for index, path in enumerate(workflow_paths)],
                                'customNodes': {CUSTOM_NODES: custom_node_hashes()}})
    _, ids, handle = open_window(folders)
    results = []
    try:
        start_unit(window, len(workflow_paths))
        schema = call(8190, '/object_info')
        save(window / 'schema.json', schema)
        started = time.monotonic()
        for index, path in enumerate(workflow_paths, start=1):
            folder = window / f'run-{index:02d}'
            graph = json.loads(Path(path).read_text())
            result = {'runId': folder.name, 'workflow': str(path), 'startedAt': time.time()}
            try:
                # 预检失败不消耗 GPU 时间，也不影响后续运行。
                result['preflight'] = validate(graph, schema)
                result['inputs'] = validate_input_files(graph, COMFY / 'input', probe_input)
                save(folder / 'inputs.json', result['inputs'])
                if time.monotonic() - started > MAX_WINDOW_SECONDS:
                    raise TimeoutError('VIDEO_WINDOW_BUDGET_EXCEEDED')
                run_one(folder, graph, result, ids, cold=index == 1)
            except BaseException as error:
                result.update(status='FAILED', errorType=type(error).__name__, error=str(error)[:300])
            finally:
                result['finishedAt'] = time.time()
                save(folder / 'result.json', result)
                results.append(result)
                print(json.dumps(result), flush=True)
            if result['status'] == 'FAILED':
                # 失败后不自动改参数继续跑，剩余运行留给人工重新开窗。
                break
    finally:
        (window / 'runtime.log').write_text(command('journalctl', '-u', UNIT, '--no-pager', '-n', '400', check=False))
        restore()
        if handle:
            handle.close()
        save(window / 'host-after.json', snapshot())
        save(window / 'summary.json', {'windowId': window_id, 'results': results})
        print(json.dumps({'windowId': window_id, 'results': results}), flush=True)


if __name__ == '__main__':
    # 三种入口：
    #   restore          —— 单独的恢复命令，任何异常中断后都靠它把生产调度恢复；
    #   run <workflows>  —— 把窗口放进取独立的 systemd 瞬态单元执行，SSH 断开也不会杀掉它；
    #   execute <workflows> —— 真正执行窗口（由 run 调用，也可在前台直接跑）。
    # 之所以必须分离：runner 若是 SSH 会话的子进程，连接断开时会被 SIGHUP 杀死，
    # finally 里的 restore() 不会执行，生产调度会一直停在暂停状态。
    if len(sys.argv) < 2:
        raise SystemExit('usage: p0_trial.py restore | run <workflow...> | execute <workflow...>')
    if sys.argv[1] == 'restore':
        restore()
    elif sys.argv[1] == 'run':
        if len(sys.argv) < 3:
            raise SystemExit('usage: p0_trial.py run <workflow...>')
        unit = 'mytools-video-p0-window-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
        # --no-block 必须加：默认 systemd-run 会一直等到单元结束，客户端会被自己的超时打断。
        command('systemd-run', '--no-block', '--unit=' + unit, '--collect', '--property=Type=oneshot',
                '--property=TimeoutStartSec=' + str(MAX_WINDOW_SECONDS + 1800),
                sys.executable, str(Path(__file__).resolve()), 'execute', *sys.argv[2:])
        print(json.dumps({'event': 'window_detached', 'unit': unit,
                          'follow': 'journalctl -u ' + unit + ' -f'}), flush=True)
    elif sys.argv[1] == 'execute':
        main(sys.argv[2:])
    else:
        raise SystemExit('unknown command: ' + sys.argv[1])
