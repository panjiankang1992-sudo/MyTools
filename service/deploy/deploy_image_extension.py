#!/usr/bin/env python3
"""图片扩展的有界增量发布；仅输出非敏感验收结果。"""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

spec = importlib.util.spec_from_file_location('base_deploy', Path(__file__).with_name('deploy_image_generation.py'))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
NAME = 'image-extension-20260914-v3'
ROOT = d.ROOT
SOURCE = Path('/tmp') / NAME
RELEASE = ROOT / 'releases' / NAME
STATE = ROOT / 'runtime' / NAME
ENV = ROOT / 'config' / (NAME + '.env')
EVAL = ROOT / 'runtime' / (NAME + '-evaluation')
OWNER = 900000000000000001
PREFIX = '/internal/v1/images'
UNITS = Path('/etc/systemd/system')
SERVICES = ['image-generation-service', 'task-scheduler-service', 'task-executor-service']
DROPIN = 'zzz-image-extension.conf'


def emit(value):
    print(json.dumps(value), flush=True)


def run(args, timeout=120):
    reply = subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout)
    if reply.returncode:
        d.write(STATE / 'last-command.log', reply.stdout + reply.stderr)
        raise RuntimeError('command_failed_' + Path(str(args[0])).name)
    return reply.stdout


def unit(name):
    return 'mytools-' + name + '.service'


def process(name):
    pid = run(['systemctl', 'show', unit(name), '-p', 'MainPID', '--value']).decode().strip()
    assert pid.isdigit() and int(pid) > 0, 'service_process_missing'
    raw = Path('/proc/' + pid + '/environ').read_bytes()
    env = dict(x.decode().split('=', 1) for x in raw.split(b'\0') if b'=' in x)
    args = [x.decode() for x in Path('/proc/' + pid + '/cmdline').read_bytes().split(b'\0') if x]
    return env, args


def image(path, method='GET', body=None, owner=OWNER, expected=200):
    code, value = d.request(23340, PREFIX + path, method, body, owner)
    assert code == expected, 'image_status_' + str(code)
    return value


def gates(enabled):
    data = d.values(ENV)
    data['IMAGE_GENERATION_EDIT_VALIDATED'] = str(enabled).lower()
    data['IMAGE_PROMPT_VALIDATED'] = str(enabled).lower()
    d.write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))


def stage():
    assert not RELEASE.exists(), 'release_exists'
    STATE.mkdir(parents=True, exist_ok=True)
    STATE.chmod(0o700)
    for name, digest in json.loads((SOURCE / 'manifest.json').read_text()).items():
        assert hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() == digest, 'digest_mismatch'
    current = {}
    for name in SERVICES:
        env, args = process(name)
        current[name] = {'args': args}
        if name == 'task-executor-service':
            executor_env = env
    d.write(STATE / 'before.json', json.dumps(current))
    RELEASE.mkdir()
    shutil.copytree(SOURCE / 'apps', RELEASE / 'apps')
    # 调度器只增加本次迁移，避免带入工作区中其他未发布业务改动。
    args = current['task-scheduler-service']['args']
    scheduler = Path(args[args.index('-jar') + 1])
    target = RELEASE / 'apps/task-scheduler-service.jar'
    shutil.copy2(scheduler, target)
    migration = 'BOOT-INF/classes/db/migration/V154__structured_image_prompt_result.sql'
    with zipfile.ZipFile(target, 'a') as archive:
        assert migration not in archive.namelist(), 'migration_already_present'
        archive.writestr(migration, (SOURCE / Path(migration).name).read_bytes())
    # 原索引及原包逐字保留，仅附加新版本。
    old_packages = Path(executor_env['TASK_EXECUTOR_SCRIPT_ROOT'])
    shutil.copytree(old_packages, RELEASE / 'task-packages')
    package = RELEASE / 'task-packages/image_generate/1.1.2'
    shutil.copytree(SOURCE / 'packages/image_generate/1.1.2', package)
    index_path = RELEASE / 'task-packages/package-index.json'
    index = json.loads(index_path.read_text())
    files = [{'path': str(p.relative_to(package)), 'sizeBytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in sorted(package.rglob('*')) if p.is_file()]
    assert not any(p['name'] == 'image_generate' and p['version'] == '1.1.2' for p in index['packages'])
    index['packages'].append({'name': 'image_generate', 'version': '1.1.2', 'entrypoint': 'scripts/main.py', 'files': files})
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    index_path.write_text(json.dumps(index, indent=2, sort_keys=True) + '\n')
    shutil.copy2(SOURCE / 'workflow.json', RELEASE / 'workflow.json')
    config = {'IMAGE_GENERATION_EDIT_VALIDATED': 'false', 'IMAGE_PROMPT_VALIDATED': 'false',
              'IMAGE_EDIT_WORKFLOW_REVISION': 'image-edit-v1', 'IMAGE_EDIT_WORKFLOW_FILE': str(RELEASE / 'workflow.json'),
              'IMAGE_EDIT_WORKFLOW_SHA256': hashlib.sha256((RELEASE / 'workflow.json').read_bytes()).hexdigest(),
              'TASK_EXECUTOR_SCRIPT_ROOT': str(RELEASE / 'task-packages'),
              'TAGGING_MODEL': executor_env.get('TAGGING_MODEL', 'huihui_ai/qwen3-vl-abliterated:8b')}
    d.write(ENV, ''.join(k + '=' + v + '\n' for k, v in config.items()))
    # 旧执行器通过补充配置注入新包所需变量，不替换 Java 实现和 SDK。
    properties = ''.join('executor.script-environments.image_generate.' + key + '=' + value + '\n' for key, value in {
        'IMAGE_GENERATION_EDIT_VALIDATED': 'true', 'IMAGE_PROMPT_VALIDATED': 'true',
        'IMAGE_EDIT_WORKFLOW_FILE': config['IMAGE_EDIT_WORKFLOW_FILE'],
        'IMAGE_EDIT_WORKFLOW_SHA256': config['IMAGE_EDIT_WORKFLOW_SHA256']}.items())
    (RELEASE / 'executor-extension.properties').write_text(properties)
    for path in [RELEASE, *RELEASE.rglob('*')]:
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750 if path.is_dir() else 0o640)
    EVAL.mkdir(exist_ok=True)
    for path in [EVAL, EVAL / 'inputs', EVAL / 'outputs']:
        path.mkdir(exist_ok=True)
        os.chown(path, pwd.getpwnam('mytools').pw_uid, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750)
    emit({'staged': True, 'release': NAME, 'packages': index['packageCount'], 'schedulerBaseSha256': hashlib.sha256(scheduler.read_bytes()).hexdigest()})


def evaluate():
    env, _ = process('task-executor-service')
    env.update(d.values(ENV))
    env.update(IMAGE_GENERATION_EDIT_VALIDATED='true', IMAGE_PROMPT_VALIDATED='true', IMAGE_GENERATION_ROOT=str(EVAL))
    jobs = image('/works?page=0')
    source = next(j for j in jobs if j['status'] == 'SUCCEEDED' and j['result'].get('images'))
    raw = image('/jobs/' + source['id'] + '/images/0')
    reference = str(uuid.uuid4())
    d.write(EVAL / 'inputs' / reference, raw, user='mytools', mode=0o640)
    d.write(EVAL / 'source.png', raw, user='mytools', mode=0o640)
    account = pwd.getpwnam('mytools')
    def demote():
        os.setgroups([])
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)
    code = "import sys,json;sys.path.insert(0,sys.argv[1]);import main;print(json.dumps(main.generate(json.loads(sys.argv[2]))))"
    results = []
    for mode in ['IMAGE_TO_IMAGE', 'IMAGE_TO_PROMPT', 'IMAGE_TO_PROMPT']:
        if len(results) == 2:
            failed=json.loads((ROOT / 'runtime/image-extension-20260914-v2/app-prompt-failed.json').read_text())
            original=Path(d.environment()['IMAGE_GENERATION_ROOT'])/'inputs'/failed['references'][0]
            reference=str(uuid.uuid4())
            d.write(EVAL/'inputs'/reference,original.read_bytes(),user='mytools',mode=0o640)
        job = str(uuid.uuid4())
        params = {'jobId': job, 'resourceId': 'krea2-local' if mode == 'IMAGE_TO_IMAGE' else 'vision-local',
                  'prompt': 'A bright red ceramic tea cup on a pale stone table, warm sunset lighting, professional product photography.',
                  'mode': mode, 'references': [reference], 'size': '1024x1024', 'count': 1, 'seed': 812,
                  'workflowRevision': 'image-edit-v1' if mode == 'IMAGE_TO_IMAGE' else 'image-prompt-v1',
                  'modelId': 'krea2-turbo' if mode == 'IMAGE_TO_IMAGE' else env['TAGGING_MODEL']}
        start = time.monotonic()
        emit({'evaluationStarted': mode, 'jobId': job})
        reply = subprocess.run([str(d.COMFY / 'venv/bin/python'), '-c', code, str(RELEASE / 'task-packages/image_generate/1.1.2/scripts'), json.dumps(params)],
                               capture_output=True, timeout=900, env=env, preexec_fn=demote)
        if reply.returncode:
            d.write(STATE / ('evaluation-' + mode + '.log'), reply.stdout + reply.stderr)
            raise RuntimeError('evaluation_failed_' + mode)
        result = {'mode': mode, 'jobId': job, 'elapsedSeconds': round(time.monotonic() - start, 2), 'fixture': 'flat-illustration' if len(results) == 2 else 'photo'}
        if mode == 'IMAGE_TO_IMAGE':
            value = (EVAL / 'outputs' / job / '0.png').read_bytes()
            assert value != raw and len(value) > 10000, 'image_not_changed'
            d.write(EVAL / 'edited.png', value, user='mytools', mode=0o640)
            result.update(bytes=len(value), sha256=hashlib.sha256(value).hexdigest())
        else:
            result['prompt'] = json.loads((EVAL / 'outputs' / job / 'prompt.json').read_text())['prompt']
        results.append(result)
        d.write(STATE / 'evaluation.json', json.dumps(results, indent=2))
        emit(result)


def activate():
    assert len(json.loads((STATE / 'evaluation.json').read_text())) == 3, 'evaluation_required'
    previous = json.loads((STATE / 'before.json').read_text())
    for name in SERVICES:
        _, args = process(name)
        assert args == previous[name]['args'], 'deployment_changed_since_stage'
    with d.task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'wait_for_active_tasks'
    code, nodes = d.request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    node = next(n for n in nodes if n['name'] == 'executor-remote-1')
    code, _ = d.request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
                        {'status': 'DRAINING', 'reason': NAME, 'expectedInstanceId': node['instanceId'], 'expectedRunningTasks': 0})
    assert code == 200, 'drain_failed'
    run(['systemctl', 'stop', unit('image-generation-service'), unit('task-executor-service')])
    for name in SERVICES:
        content = '[Service]\nEnvironmentFile=' + str(ENV) + '\n'
        args = previous[name]['args'][:]
        if name in ['image-generation-service', 'task-scheduler-service']:
            args[args.index('-jar') + 1] = str(RELEASE / 'apps' / (name + '.jar'))
        else:
            index = next(i for i, value in enumerate(args) if value.startswith('--spring.config.additional-location='))
            args[index] += ',' + str(RELEASE / 'executor-extension.properties')
        assert all(' ' not in value for value in args), 'unexpected_command_quoting'
        content += 'ExecStart=\nExecStart=' + ' '.join(args) + '\n'
        d.write(UNITS / (unit(name) + '.d') / DROPIN, content, mode=0o644)
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'restart', unit('task-scheduler-service')])
    d.health(23410)
    run(['systemctl', 'start', unit('task-executor-service'), unit('image-generation-service')])
    d.health(23340)
    time.sleep(5)
    code, nodes = d.request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    node = next(n for n in nodes if n['name'] == 'executor-remote-1')
    code, _ = d.request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
                        {'status': 'ONLINE', 'reason': NAME, 'expectedInstanceId': node['instanceId']})
    assert code == 200
    gates(True)
    run(['systemctl', 'restart', unit('image-generation-service')])
    d.health(23340)
    emit({'activated': True, 'models': image('/models')})


def rollback():
    # 保留数据库及历史包，关闭新入口后恢复图片服务与执行器的旧启动参数。
    gates(False)
    with d.task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'rollback_wait_for_tasks'
    for name in ['image-generation-service']:
        (UNITS / (unit(name) + '.d') / DROPIN).unlink(missing_ok=True)
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'restart', unit('image-generation-service')])
    d.health(23340)
    emit({'rolledBack': True, 'newModesHidden': True, 'newPackageRetainedForTaskSnapshots': True})


if __name__ == '__main__':
    os.umask(0o077)
    try:
        assert os.geteuid() == 0
        {'stage': stage, 'evaluate': evaluate, 'activate': activate, 'rollback': rollback}[sys.argv[1]]()
    except Exception as error:
        import traceback
        emit({'failed': True, 'type': type(error).__name__, 'line': traceback.extract_tb(error.__traceback__)[-1].lineno,
              'reason': str(error) if isinstance(error, (RuntimeError, AssertionError)) else 'private_diagnostic_suppressed'})
        sys.exit(1)
