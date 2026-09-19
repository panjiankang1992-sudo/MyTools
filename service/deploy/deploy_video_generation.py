#!/usr/bin/env python3
"""部署视频生成服务的固定版本；私密配置只在 Ubuntu 本机读取和写入。

用法（在 Ubuntu 上以 root 执行，源码目录由发布流程放到 /tmp/<NAME>）：

    python3 deploy_video_generation.py stage
    python3 deploy_video_generation.py activate
    python3 deploy_video_generation.py enable
    python3 deploy_video_generation.py verify     # 维护窗口内跑真实任务的验收
    python3 deploy_video_generation.py rollback

`stage` 只写文件与数据库账号，不改动线上服务；`activate` 才切换执行器与调度器。
逐模式验收开关默认全部为 false：`verify` 通过之前，App 只会看到"未验收"的能力目录。

`/tmp/<NAME>` 必须包含（由 `assemble_release.py` 的产物加上视频包与工具）：

    manifest.json                 扁平映射 {相对路径: sha256}，用于校验传输完整性
    apps/<service>.jar            调度器、执行器、网关与 video-generation-service
    packages/video_generate/<PACKAGE_VERSION> 任务包（隐含 tests 不随发布分发）
    tools/emit_workflow_specs.py  生成固定工作流规格
    tools/workflow.py             规格生成依赖的构图代码
    tools/prepare_control.py      开发期控制信号工具（随发布归档，便于复现证据）
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import secrets
import shlex
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

# 运维依赖只放在隔离运行目录（P0 已放好的 pymysql），不修改系统 Python 环境。
sys.path.insert(0, '/opt/yuyutian/mytools/runtime/video-generation/ops-python')

ROOT = Path('/opt/yuyutian/mytools')
# 发布名按里程碑可覆盖：VIDEO_DEPLOY_NAME=video-production-20260919-v1 …
NAME = os.environ.get('VIDEO_DEPLOY_NAME', 'video-production-20260914-v1')
RELEASE = ROOT / 'releases' / NAME
# 本发布携带的视频任务包版本；包内容不可变，改动必须升版本并同步调度器迁移。
PACKAGE_VERSION = '1.0.1'
# 历史里程碑的脚本包根：基线若缺少其中的同名新版本，发布会把它们一并带上。
BASELINE_PACKAGE_ROOTS = [ROOT / 'releases' / name / 'task-packages' for name in (
    'image-extension-20260914-v3', 'image-extension-20260914-v2', 'image-production-20260913-v1')]
STATE = ROOT / 'runtime' / NAME
SOURCE = Path('/tmp') / NAME
COMFY = ROOT / 'runtime/video-generation/runtime-v1'
RUNTIME = ROOT / 'runtime/video-generation'
ENV = ROOT / 'config/video-generation.env'
UNITS = Path('/etc/systemd/system')
SERVICES = ['task-scheduler-service', 'task-executor-service', 'mytools-gateway']
CLUSTER_ID = '48615d43-0768-4d04-aa73-78fc97f57e47'
TASK_ID = '44544153-c55b-4124-8d7e-0365f70c3893'
# 视频任务与图片任务共用一个执行器节点，集群列表必须是两者的并集，否则后安装的一方会挤掉另一方。
CLUSTERS = ('media,reader,reader-probe-orchestration,download,download-orchestration,messaging,asset,'
            'drive,identity,storage,image-generation,video-generation')
# 传递到任务包进程的视频变量；服务自己的令牌、密码与路由开关不进入任务环境。
WORKER_ENVIRONMENT = (
    'VIDEO_LOCAL_VALIDATED', 'VIDEO_GPU_COORDINATION_VALIDATED',
    'VIDEO_T2V_VALIDATED', 'VIDEO_FIRST_FRAME_VALIDATED', 'VIDEO_REFERENCES_VALIDATED',
    'VIDEO_FIRST_LAST_VALIDATED', 'VIDEO_RESTYLE_VALIDATED', 'VIDEO_MASKED_VALIDATED',
    'VIDEO_GENERATION_ROOT', 'VIDEO_COMFY_URL', 'VIDEO_COMFY_OUTPUT_DIR', 'VIDEO_GPU_LOCK_FILE',
    'VIDEO_WORKFLOW_INDEX_FILE', 'VIDEO_WORKFLOW_INDEX_SHA256', 'VIDEO_MIN_FREE_MIB',
    'VIDEO_INFERENCE_TIMEOUT_SECONDS', 'TAGGING_MODEL', 'TAGGING_SERVICE_URL',
)
MODE_FLAGS = {
    'FIRST_FRAME': 'VIDEO_FIRST_FRAME_VALIDATED',
    'TEXT_TO_VIDEO': 'VIDEO_T2V_VALIDATED',
    'SUBJECT_REFERENCES': 'VIDEO_REFERENCES_VALIDATED',
    'FIRST_LAST_FRAMES': 'VIDEO_FIRST_LAST_VALIDATED',
    'STRUCTURE_RESTYLE': 'VIDEO_RESTYLE_VALIDATED',
    'MASKED_EDIT': 'VIDEO_MASKED_VALIDATED',
}
# 维护窗口内要暂停的、含 GPU 步骤的任务定义（与 P0 使用的同一批）。
GPU_PACKAGES = ('image_generate', 'media_generate_tags')
EDGE_COLUMNS = 40
WHITE_EDGE_LIMIT = 0.80
# 补边必须是中性灰：亮度落在 0.502 附近，且三通道互不相差太多。
# 只测亮度会漏掉"被模型改色"的边——实测蓝边亮度 0.36、黄边约 0.6，都能骗过白边判据。
EDGE_BRIGHTNESS_BAND = 0.06
EDGE_CHANNEL_SPREAD_LIMIT = 12
# 复验必须用 P0 十四个窗口用的同一张原图（1024×1024，952822 字节）。
VERIFY_INPUT_SHA256 = '712c98518e3c9d40c142428fc6b7ca263e59723d9992e0d9519f7cbe71a6a8a3'
VERIFY_INPUT_CANDIDATES = (
    '/opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/input/p0-subject-a.png',
    '/opt/yuyutian/mytools/runtime/video-generation/input/p0-subject-a.png',
)


def emit(value):
    print(json.dumps(value), flush=True)


def run(args, timeout=180):
    result = subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout)
    if result.returncode:
        if STATE.exists():
            write(STATE / 'last-command.log', result.stderr)
        raise RuntimeError('command_failed_' + Path(str(args[0])).name)
    return result.stdout


def write(path, content, user='root', mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode())
    path.chmod(mode)
    os.chown(path, pwd.getpwnam(user).pw_uid, pwd.getpwnam(user).pw_gid)


def values(path):
    data = {}
    if not Path(path).exists():
        return data
    for line in Path(path).read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and not key.startswith('#'):
            data[key] = shlex.split(value)[0] if value else ''
    return data


def environment():
    data = values(ROOT / 'config/services.env')
    if ENV.exists():
        data.update(values(ENV))
    return data


def request(port, path, method='GET', body=None, owner=None, identity='operator', timeout=30):
    data = environment()
    headers = {'Content-Type': 'application/json'}
    if port == 23410:
        if identity == 'video':
            headers.update({'X-Task-Service-Id': 'video-generation-service',
                            'X-Task-Business-Token': data['TASK_BUSINESS_VIDEO_GENERATION_TOKEN']})
        else:
            headers.update({'X-Task-Service-Id': 'task-operator-service',
                            'X-Task-Internal-Token': data['TASK_OPERATOR_INTERNAL_TOKEN']})
    if port == 23341:
        headers['Authorization'] = 'Bearer ' + data['VIDEO_GENERATION_INTERNAL_TOKEN']
        headers['X-Owner-Id'] = str(owner or 1)
    req = urllib.request.Request('http://127.0.0.1:' + str(port) + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read(8000000)
        content_type = response.headers.get('Content-Type', '')
        if content_type.startswith('video/') or content_type.startswith('image/'):
            return response.status, raw, content_type
        return response.status, (json.loads(raw) if raw else None), content_type


def health(port, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            code, result, _ = request(port, '/actuator/health')
            if code == 200 and result.get('status') == 'UP':
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('health_failed_' + str(port))


def available_mib():
    """读取主机可用内存；视频运行时需要约 12GiB 常驻上限，必须留出安全余量。"""
    for line in Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            return int(line.split()[1]) // 1024
    raise RuntimeError('memory_state_unreadable')


def task_connection():
    import pymysql
    data = environment()
    return pymysql.connect(host=data.get('TASK_DB_HOST', '127.0.0.1'), port=int(data.get('TASK_DB_PORT', '3306')),
                           user=data.get('TASK_DB_USER') or data['TASK_DB_USERNAME'],
                           password=data['TASK_DB_PASSWORD'], database='mytools_task')


def spec_index():
    """生成工作流规格并返回索引摘要，规格文件随发布一起固定。"""
    target = RELEASE / 'workflow-specs'
    target.mkdir(parents=True, exist_ok=True)
    run([sys.executable, str(SOURCE / 'tools/emit_workflow_specs.py'), '--output-dir', str(target)])
    index = target / 'index.json'
    for path in [target, *target.rglob('*')]:
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750 if path.is_dir() else 0o640)
    return index, hashlib.sha256(index.read_bytes()).hexdigest()


def release_property_files(pattern):
    """列出现有发布里的属性文件，用于汇总既有的集群与标签授权。"""
    return sorted((ROOT / 'releases').glob(pattern))


def merge_authorization(cluster_prefix, label_key, label_prefix):
    """汇总既有授权并加入本次的集群与标签。

    只写自己那一条会取消其它发布的授权：executor 会同时声明多个集群，
    任何一个不在允许列表里都会让节点注册直接 403。
    """
    clusters = {item for item in CLUSTERS.split(',') if item}
    labels = {}
    files = [ROOT / 'runtime/adaptation-production-20260911/scheduler/application.properties']
    files += release_property_files('*/scheduler.properties')
    files += release_property_files('*/executor*.properties')
    for path in files:
        data = values(path)
        for cluster in data.get(cluster_prefix, '').split(','):
            if cluster.strip():
                clusters.add(cluster.strip())
        for key, value in data.items():
            match = re.fullmatch(re.escape(label_prefix) + r'\[(.+)\]', key)
            if match:
                labels[match.group(1)] = value
    labels[label_key] = 'enabled'
    return ','.join(sorted(clusters)), labels


def current_config_locations(service):
    """读取当前生效的 Spring 配置片段列表，避免替换 ExecStart 时丢掉其它发布的额外配置。"""
    output = run(['systemctl', 'show', 'mytools-' + service, '-p', 'ExecStart']).decode()
    match = re.search(r'--spring.config.additional-location=(\S+)', output)
    locations = [item for item in (match.group(1).split(',') if match else []) if item]
    return [item for item in locations if not item.startswith(str(RELEASE))]


def script_environment_lines(data):
    """把视频任务需要的变量写成按包隔离的 script-environments。"""
    return ''.join('executor.script-environments.video_generate.' + key + '=' + str(data.get(key, '')) + '\n'
                   for key in WORKER_ENVIRONMENT if data.get(key, '') != '')


def write_executor_properties(data):
    """重写执行器属性：并集授权 + 视频包的按包环境。"""
    clusters, labels = merge_authorization('executor.cluster-names', 'video.generation', 'executor.labels')
    content = ('executor.cluster-names=' + clusters + '\n'
               + ''.join('executor.labels[' + key + ']=' + value + '\n' for key, value in sorted(labels.items()))
               + script_environment_lines(data))
    write(RELEASE / 'executor.properties', content, mode=0o640)
    os.chown(RELEASE / 'executor.properties', 0, pwd.getpwnam('mytools').pw_gid)


def ensure_task_runtime():
    """执行器用的 Python 必须能 import numpy；缺失时安装固定版本，否则视频任务必然失败。"""
    interpreter = values(ROOT / 'config/services.env').get('TASK_EXECUTOR_PYTHON_EXECUTABLE',
                                                           str(ROOT / 'releases/current/venv/bin/python3'))
    probe = subprocess.run([interpreter, '-c', 'import numpy'], capture_output=True)
    if probe.returncode == 0:
        return
    # 默认索引（files.pythonhosted.org）在本机实测会卡住下载；镜像在目标网络里 40MB/s 级可用，
    # 因此先用默认源、失败再用镜像重试，保证离线/受限网络下也能装成。
    indexes = [('https://pypi.org/simple', None),
               ('https://pypi.tuna.tsinghua.edu.cn/simple', 'pypi.tuna.tsinghua.edu.cn')]
    output = ''
    for index, host in indexes:
        command = [interpreter, '-m', 'pip', 'install', '--no-input', '--disable-pip-version-check',
                   '--only-binary=:all:', '--timeout', '60', '--retries', '3', '-i', index, 'numpy==2.5.3']
        if host:
            command += ['--trusted-host', host]
        done = subprocess.run(command, capture_output=True, timeout=1800)
        output = done.stdout.decode()[-300:] + done.stderr.decode()[-300:]
        if subprocess.run([interpreter, '-c', 'import numpy'], capture_output=True).returncode == 0:
            return
    raise RuntimeError('task_runtime_numpy_missing ' + output)


def current_executor_jar():
    """取线上正在运行的执行器 jar 路径。

    执行器代码自 2026-09-13 起未变化（新发布与线上产物的类清单与大小逐项一致），
    因此允许发布里不带这个 38MB 的 jar，改从线上版本复用并记录来源与摘要。
    """
    output = run(['systemctl', 'show', 'mytools-task-executor-service', '-p', 'ExecStart']).decode()
    match = re.search(r'-jar (\S*task-executor-service\.jar)', output)
    assert match, 'executor_jar_unresolved'
    path = Path(match.group(1))
    if path.is_file():
        return path
    # drop-in 可能指向一个尚未完成（或已回滚）的发布：退回到其它发布里现存的执行器 jar，
    # 否则复用步骤会把整个 stage 卡死（实测踩过一次：activate 失败后 drop-in 指向半成品发布）。
    candidates = sorted((ROOT / 'releases').glob('*/apps/task-executor-service.jar'),
                        key=lambda item: item.stat().st_mtime, reverse=True)
    assert candidates, 'executor_jar_missing'
    return candidates[0]


def current_script_root():
    """取线上执行器实际在用的脚本包根。

    不能读 config/image-generation.env 里的旧值：那是 image-production 的包根，
    只带 image_generate 1.0.0；而任务定义可能已经钉到 1.1.x，节点声明不出来就会导致
    图片任务排到派发超时。以运行中进程的环境变量为准，其次才回退到配置文件。
    """
    output = run(['systemctl', 'show', 'mytools-task-executor-service', '-p', 'MainPID']).decode()
    match = re.search(r'MainPID=(\d+)', output)
    if match and match.group(1) != '0':
        try:
            raw = Path(f'/proc/{match.group(1)}/environ').read_bytes()
            for item in raw.split(b'\0'):
                if item.startswith(b'TASK_EXECUTOR_SCRIPT_ROOT='):
                    path = Path(item.split(b'=', 1)[1].decode())
                    if path.is_dir():
                        return path
        except OSError:
            pass
    fallback = values(ROOT / 'config/image-generation.env').get('TASK_EXECUTOR_SCRIPT_ROOT')
    assert fallback and Path(fallback).is_dir(), 'executor_package_root_missing'
    return Path(fallback)


def pinned_packages():
    """读已启用任务定义钉住的脚本包版本，用于发布前预检。"""
    query = ("SELECT ts.script_package, ts.script_version FROM task_step_definition ts "
             "JOIN task_definition td ON td.id=ts.task_definition_id "
             "WHERE td.enabled=1 AND ts.enabled=1")
    output = run(['mysql', '--defaults-file=/etc/mysql/debian.cnf', '-N', '-B', 'mytools_task', '-e', query]).decode()
    pinned = set()
    for line in output.splitlines():
        name, _, version = line.partition('\t')
        if name.strip() and version.strip():
            pinned.add((name.strip(), version.strip()))
    return pinned


def assert_pinned_packages_indexed():
    """发布前预检：索引里必须能看到所有被钉住的包版本，否则执行器无法派发。"""
    index = json.loads((RELEASE / 'task-packages/package-index.json').read_text())
    available = {(item['name'], item['version']) for item in index['packages']}
    missing = sorted(f'{name}:{version}' for name, version in pinned_packages() if (name, version) not in available)
    assert not missing, 'pinned_package_missing:' + ','.join(missing)


def assert_result_schemas_registered():
    """发布前预检：包里每个任务包的结果 schema 必须与调度器注册的一致。

    执行器上报结果时，调度器按 task_definition.result_schema 校验。包改了结果字段却没同步注册迁移，
    任务会在推理跑完（8 分钟以上）之后才以 TASK_RESULT_SCHEMA_INVALID 失败——实测踩过一次，
    因此这里把它变成发布前的硬门禁。
    """
    index = json.loads((RELEASE / 'task-packages/package-index.json').read_text())
    # 只核对本发布负责的包：别的服务线的历史包由它们自己的发布把关。实测 media_submit_analysis:1.0.0
    # 的 result.schema.json 不是合法 JSON，那是媒体服务的问题，不该让视频发布为它背锅。
    pinned = pinned_packages()
    mismatched = []
    for item in index['packages']:
        if item['name'] != 'video_generate' or (item['name'], item['version']) not in pinned:
            continue
        version_root = RELEASE / 'task-packages' / item['name'] / item['version']
        manifest = (version_root / 'manifest.yaml').read_text(encoding='utf-8')
        match = re.search(r'^\s*schema:\s*(\S+)\s*$', manifest, re.MULTILINE)
        if not match:
            continue
        schema_path = version_root / match.group(1)
        if not schema_path.is_file():
            mismatched.append(f"{item['name']}:{item['version']}:schema_missing")
            continue
        # 库里可能有多条定义钉同一版本，逐条比对。
        query = ('SELECT td.name, td.result_schema FROM task_definition td '
                 'JOIN task_step_definition ts ON ts.task_definition_id=td.id '
                 'WHERE ts.script_package=%s AND ts.script_version=%s')
        output = run(['mysql', '--defaults-file=/etc/mysql/debian.cnf', '-N', '-B', 'mytools_task',
                      '-e', f"SELECT td.name, td.result_schema FROM task_definition td "
                            f"JOIN task_step_definition ts ON ts.task_definition_id=td.id "
                            f"WHERE ts.script_package='{item['name']}' "
                            f"AND ts.script_version='{item['version']}'"]).decode()
        local = json.dumps(json.loads(schema_path.read_text()), sort_keys=True, separators=(',', ':'))
        for line in output.splitlines():
            name, _, registered = line.partition('\t')
            if not name.strip():
                continue
            try:
                remote = json.dumps(json.loads(registered), sort_keys=True, separators=(',', ':'))
            except ValueError:
                mismatched.append(name + ':schema_unparsable')
                continue
            if remote != local:
                mismatched.append(name + ':schema_out_of_sync')
    assert not mismatched, 'result_schema_preflight_failed:' + ','.join(sorted(mismatched))


def package_files(root):
    """列出任务包里参与索引的文件（排除测试与缓存），与索引算法保持一致。"""
    files = []
    for file in sorted(root.rglob('*')):
        if file.is_file() and '__pycache__' not in file.parts and 'tests' not in file.relative_to(root).parts:
            files.append({'path': str(file.relative_to(root)), 'sizeBytes': file.stat().st_size,
                          'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
    return files


def stage():
    # 发布目录不可复用（发布不可变）；env 允许已存在——那是同一服务线的上一版，
    # 重发必须沿用既有令牌与库口令，否则调度器/网关/服务三方对不上。
    assert not RELEASE.exists(), 'stage_already_exists'
    previous = values(ENV) if ENV.exists() else {}
    required = ['manifest.json', f'packages/video_generate/{PACKAGE_VERSION}/manifest.yaml',
                'tools/emit_workflow_specs.py', 'tools/workflow.py', 'tools/prepare_control.py']
    for name in required:
        assert (SOURCE / name).is_file(), 'source_missing_' + name.replace('/', '_')
    # 三个共享/新服务的 jar 由 `assemble_release_delta.py` 在目标机上用"基线 + 增量"重组，
    # 它逐成员校验过内容；这里只接受校验通过的结果，不接受裸 jar。
    verification = SOURCE / 'jar-verification.json'
    assert verification.is_file(), 'jar_verification_missing'
    verified = {item['jar']: item for item in json.loads(verification.read_text())}
    for service in ['task-scheduler-service', 'mytools-gateway', 'video-generation-service']:
        entry = verified.get(service + '.jar')
        assert (SOURCE / 'apps' / (service + '.jar')).is_file(), 'app_missing_' + service
        assert entry and entry.get('verified') is True, 'jar_unverified_' + service
    manifest = json.loads((SOURCE / 'manifest.json').read_text())
    for name, digest in manifest.items():
        assert hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() == digest, 'artifact_digest_mismatch'
    STATE.mkdir(parents=True, exist_ok=True)
    STATE.chmod(0o700)
    RELEASE.mkdir()
    shutil.copytree(SOURCE / 'apps', RELEASE / 'apps')
    reused = {}
    if not (RELEASE / 'apps/task-executor-service.jar').is_file():
        source_jar = current_executor_jar()
        shutil.copy2(source_jar, RELEASE / 'apps/task-executor-service.jar')
        reused['task-executor-service.jar'] = {
            'source': str(source_jar),
            'sha256': hashlib.sha256((RELEASE / 'apps/task-executor-service.jar').read_bytes()).hexdigest()}
    # 延续普通节点的完整历史脚本目录，只增加视频包；图片与标签包必须原样保留。
    base = current_script_root()
    assert base.is_dir(), 'executor_package_root_missing'
    shutil.copytree(base, RELEASE / 'task-packages')
    # 基线与本发布可能带同一个包版本（同一服务线的后续发布）：此时必须逐文件核对内容一致，
    # 既避免 copytree 撞车，也守住"同版本即同内容"的不可变语义。
    source_package = SOURCE / f'packages/video_generate/{PACKAGE_VERSION}'
    package_root = RELEASE / 'task-packages/video_generate' / PACKAGE_VERSION
    source_files = package_files(source_package)
    if package_root.exists():
        assert package_files(package_root) == source_files, 'package_content_drift'
    else:
        shutil.copytree(source_package, package_root)
    index_path = RELEASE / 'task-packages/package-index.json'
    index = json.loads(index_path.read_text())
    existing = next((item for item in index['packages']
                     if item['name'] == 'video_generate' and item['version'] == PACKAGE_VERSION), None)
    if existing is not None:
        assert existing['files'] == source_files, 'package_index_drift'
    else:
        index['packages'].append({'name': 'video_generate', 'version': PACKAGE_VERSION,
                                  'entrypoint': 'scripts/main.py', 'files': source_files})
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True,
                                                      separators=(',', ':')).encode()).hexdigest()
    # 基线可能比某些包旧：把基线上没有、而里程碑历史里存在的同名新版本一并带上。
    for extra in sorted(BASELINE_PACKAGE_ROOTS):
        if not extra.is_dir():
            continue
        for manifest in sorted(extra.glob('*/[0-9]*/manifest.yaml')):
            name, version = manifest.parent.parent.name, manifest.parent.name
            target = RELEASE / 'task-packages' / name / version
            if target.exists():
                continue
            shutil.copytree(manifest.parent, target)
            extra_files = package_files(target)
            entrypoint = 'scripts/main.py'
            for line in manifest.read_text(encoding='utf-8').splitlines():
                match = re.fullmatch(r'entrypoint:\s*(.*?)\s*', line)
                if match:
                    entrypoint = match.group(1).strip('\'"')
                    break
            index['packages'].append({'name': name, 'version': version, 'entrypoint': entrypoint,
                                      'files': extra_files})
            index['packages'].sort(key=lambda item: (item['name'], item['version']))
            index['packageCount'] = len(index['packages'])
            index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True,
                                                              separators=(',', ':')).encode()).hexdigest()
    index_path.write_text(json.dumps(index, indent=2, sort_keys=True) + '\n')
    spec_path, spec_digest = spec_index()
    shutil.copy2(SOURCE / 'tools/prepare_control.py', RELEASE / 'prepare_control.py')
    # 预检必须在权限收敛前做（执行器用户要能读到包），这里紧跟索引写完立即校验。
    assert_pinned_packages_indexed()
    # 结果 schema 的一致性放在 activate 里校验：修复它的迁移可能就在本次发布里，
    # 在 stage 阶段校验会变成"先有鸡还是先有蛋"。
    for path in [RELEASE, *RELEASE.rglob('*')]:
        if 'workflow-specs' in path.parts:
            continue
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750 if path.is_dir() else 0o640)
    data = {
        # 首次发布生成新令牌；后续发布沿用上一版，避免换令牌造成三方不一致。
        'VIDEO_GENERATION_INTERNAL_TOKEN': previous.get('VIDEO_GENERATION_INTERNAL_TOKEN') or secrets.token_urlsafe(32),
        'TASK_BUSINESS_VIDEO_GENERATION_TOKEN': previous.get('TASK_BUSINESS_VIDEO_GENERATION_TOKEN') or secrets.token_urlsafe(32),
        'VIDEO_GENERATION_DB_PASSWORD': previous.get('VIDEO_GENERATION_DB_PASSWORD') or secrets.token_urlsafe(32),
        'VIDEO_GENERATION_DB_USER': 'mytools_video_generation',
        'VIDEO_GENERATION_ROOT': str(RUNTIME),
        'VIDEO_GENERATION_ROUTE_ENABLED': 'false',
        # 引擎级门禁沿用 P0 的结论：本机模型与共享显存锁都已验证；逐模式开关先全部关闭。
        'VIDEO_LOCAL_VALIDATED': 'true',
        'VIDEO_GPU_COORDINATION_VALIDATED': 'true',
        'VIDEO_GPU_LOCK_FILE': str(ROOT / 'runtime/image-generation/gpu.lock'),
        'VIDEO_COMFY_URL': 'http://127.0.0.1:8190',
        'VIDEO_COMFY_OUTPUT_DIR': str(COMFY / 'ComfyUI/output'),
        'VIDEO_WORKFLOW_INDEX_FILE': str(spec_path),
        'VIDEO_WORKFLOW_INDEX_SHA256': spec_digest,
        # 49 帧实测峰值约 14.5 GiB；门槛略高于实测峰值，宁可直接拒绝也不要 OOM。
        'VIDEO_MIN_FREE_MIB': '14848',
        # 49 帧推理实测 8–17 分钟；超时上限留出两倍余量，超时后任务被判失败而不是无限占用 GPU。
        'VIDEO_INFERENCE_TIMEOUT_SECONDS': '2400',
        'VIDEO_MODEL_REVISION': 'wan2.1-vace-1.3b-fp16',
        'VIDEO_WORKFLOW_REVISION': 'video-vace-1.3b-v1',
        'TASK_EXECUTOR_SCRIPT_ROOT': str(RELEASE / 'task-packages'),
        # 任务包里的显存租约需要确认"驻留的标签模型就是共用的那一个"。
        'TAGGING_MODEL': (values(ROOT / 'config/image-extension-20260914-v3.env').get('TAGGING_MODEL')
                          or 'huihui_ai/qwen3-vl-abliterated:8b'),
        'TAGGING_SERVICE_URL': values(ROOT / 'config/services.env').get('TAGGING_SERVICE_URL',
                                                                       'http://127.0.0.1:11434'),
    }
    for flag in MODE_FLAGS.values():
        data[flag] = 'false'
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    import pymysql
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', autocommit=True) as db, db.cursor() as cursor:
        cursor.execute('CREATE DATABASE IF NOT EXISTS mytools_video_generation CHARACTER SET utf8mb4')
        cursor.execute('CREATE USER IF NOT EXISTS %s@%s IDENTIFIED BY %s',
                       ('mytools_video_generation', 'localhost', data['VIDEO_GENERATION_DB_PASSWORD']))
        cursor.execute('GRANT ALL PRIVILEGES ON mytools_video_generation.* TO %s@%s',
                       ('mytools_video_generation', 'localhost'))
    for path in [RUNTIME, RUNTIME / 'uploads', RUNTIME / 'outputs', RUNTIME / 'work',
                 RUNTIME / 'workflows', COMFY / 'ComfyUI/output', COMFY / 'ComfyUI/input',
                 Path('/opt/yuyutian/logs/mytools/video-generation-service')]:
        path.mkdir(parents=True, exist_ok=True)
        os.chown(path, pwd.getpwnam('mytools').pw_uid, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750)
    write_executor_properties(data)
    allowed, trusted = merge_authorization('task.node-registration.allowed-cluster-names', 'video.generation',
                                           'task.node-registration.trusted-labels')
    write(RELEASE / 'scheduler.properties',
          'task.node-registration.allowed-cluster-names=' + allowed + '\n'
          + ''.join('task.node-registration.trusted-labels[' + key + ']=' + value + '\n'
                    for key, value in sorted(trusted.items())), mode=0o640)
    os.chown(RELEASE / 'scheduler.properties', 0, pwd.getpwnam('mytools').pw_gid)
    for service in SERVICES:
        write(STATE / (service + '.before.txt'), run(['systemctl', 'cat', 'mytools-' + service]))
    if reused:
        write(STATE / 'reused-artifacts.json', json.dumps(reused, indent=2) + '\n')
    # 记录重组后每个 jar 的摘要与基线来源，便于事后审计。
    write(STATE / 'jar-verification.json', json.dumps(verified, indent=2, sort_keys=True) + '\n')
    emit({'staged': True, 'release': NAME, 'workflowSpecSha256': spec_digest, 'routeEnabled': False,
          'reusedArtifacts': sorted(reused)})


def install_units():
    additions = {'task-scheduler-service': RELEASE / 'scheduler.properties',
                 'task-executor-service': RELEASE / 'executor.properties'}
    for service in SERVICES:
        locations = current_config_locations(service)
        extra = additions.get(service)
        if extra is not None and str(extra) not in locations:
            locations.append(str(extra))
        extras = ' --spring.config.additional-location=' + ','.join(locations) if locations else ''
        # 生效顺序由文件名决定：必须排在所有既有 drop-in 之后，否则本次发布不会生效（静默回退）。
        write(UNITS / ('mytools-' + service + '.service.d/zzzzz-video-generation.conf'),
              '[Service]\nEnvironmentFile=' + str(ENV) + '\nExecStart=\nExecStart=/usr/bin/java -jar '
              + str(RELEASE / 'apps' / (service + '.jar')) + extras + '\n', mode=0o644)
    write(UNITS / 'mytools-video-generation-service.service',
          '[Unit]\nDescription=MyTools video generation\nAfter=network-online.target mytools-task-scheduler-service.service\n'
          '[Service]\nUser=mytools\nGroup=mytools\nEnvironmentFile=' + str(ROOT / 'config/services.env')
          + '\nEnvironmentFile=' + str(ENV) + '\nWorkingDirectory=' + str(RUNTIME)
          # 单次上传可达 200MB，原始字节会完整进入堆；256m 会直接 OOM。
          + '\nExecStart=/usr/bin/java -Xmx1g -jar ' + str(RELEASE / 'apps/video-generation-service.jar')
          + '\nRestart=on-failure\nUMask=0027\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=full\n'
            'ProtectHome=true\nStandardOutput=append:/opt/yuyutian/logs/mytools/video-generation-service/service.log\n'
            'StandardError=inherit\n[Install]\nWantedBy=mytools-services.target\n', mode=0o644)
    # 视频运行时长期驻留（空闲时权重已卸载），由共享 flock 与两个 Comfy 的 /free 串行化权重加载。
    write(UNITS / 'mytools-video-comfy.service',
          '[Unit]\nDescription=MyTools VACE 1.3B video inference\nAfter=network-online.target\n'
          '[Service]\nUser=mytools\nGroup=mytools\nWorkingDirectory=' + str(COMFY / 'ComfyUI')
          + '\nExecStart=' + str(COMFY / 'venv/bin/python')
          + ' main.py --listen 127.0.0.1 --port 8190 --lowvram --reserve-vram 2 --disable-pinned-memory'
            ' --disable-all-custom-nodes --whitelist-custom-nodes wan_vace_multi_reference'
            ' --disable-api-nodes --preview-method none'
            '\nMemoryHigh=10G\nMemoryMax=12G\nMemorySwapMax=0\nTimeoutStopSec=30\nNoNewPrivileges=true\n'
            'PrivateTmp=true\nRestart=on-failure\nRestartSec=10\n[Install]\nWantedBy=mytools-services.target\n',
          mode=0o644)
    run(['systemctl', 'daemon-reload'])
    for service in SERVICES:
        legacy = UNITS / ('mytools-' + service + '.service.d/zz-video-generation.conf')
        if legacy.exists():
            legacy.unlink()
    run(['systemctl', 'daemon-reload'])
    verify_effective_jars()


def verify_effective_jars():
    """确认三个共享服务真的会运行本次发布的 jar，避免 drop-in 被更靠后的文件盖掉。"""
    problems = []
    for service in SERVICES:
        output = run(['systemctl', 'show', 'mytools-' + service, '-p', 'ExecStart']).decode()
        if str(RELEASE / 'apps' / (service + '.jar')) not in output:
            problems.append(service)
    if problems:
        raise RuntimeError('dropin_not_effective_' + ','.join(problems))


def activate():
    assert RELEASE.exists() and ENV.exists()
    with task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'wait_for_active_executions'
    code, nodes, _ = request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    node = next(n for n in nodes if n['name'] == 'executor-remote-1')
    code, _, _ = request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
                         {'status': 'DRAINING', 'reason': NAME, 'expectedInstanceId': node['instanceId'],
                          'expectedRunningTasks': 0})
    assert code == 200, 'drain_failed'
    write(STATE / 'node.json', json.dumps({'id': node['id']}))
    install_units()
    run(['systemctl', 'stop', 'mytools-task-executor-service'])
    # 重启调度器会执行本发布携带的迁移，从而登记 video_generate 任务、发布审计表与结果 schema。
    run(['systemctl', 'restart', 'mytools-task-scheduler-service'])

    ensure_task_runtime()
    run(['systemctl', 'start', 'mytools-task-executor-service', 'mytools-video-generation-service'])
    # 视频运行时常驻上限 12GiB，与图片 Comfy（MemoryMax=18G）并存时必须有足够可回收余量，
    # 否则宁可不起视频运行时也不要让主机进入换页。
    # P0 的 18GiB 门槛是在"图片 Comfy 已停止"的前提下标定的；生产要保持图片服务常驻，
    # 因此按"视频单元 12GiB 上限 + 2GiB 余量"设 14GiB 起点，并靠 cgroup 上限与
    # MemorySwapMax=0 保证主机不会被视频任务拖入换页（P0 实测峰值时主机可用仍有 14.6GiB）。
    before = available_mib()
    if before < 14 * 1024:
        raise RuntimeError('host_ram_gate_failed ' + str(before))
    run(['systemctl', 'start', 'mytools-video-comfy'])
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            code, nodes, _ = request(23410, '/api/v1/execution-topology/nodes')
            if code == 200:
                break
        except Exception:
            pass
        time.sleep(2)
    else:
        raise RuntimeError('scheduler_start_failed')
    node = next(n for n in nodes if n['name'] == 'executor-remote-1')
    code, _, _ = request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
                         {'status': 'ONLINE', 'reason': NAME, 'expectedInstanceId': node['instanceId']})
    assert code == 200
    health(23410)
    # 调度器健康（迁移已跑完）之后再校验契约：执行器上报结果时按注册的 result_schema 校验，
    # 包改了结果字段却没同步注册，任务会在推理 8 分钟之后才失败。
    assert_result_schemas_registered()
    # 视频运行时首次加载模型较慢，只确认端口可应答；它不参与任务派发前的可用性判定。
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('http://127.0.0.1:8190/queue', timeout=5) as reply:
                if reply.status == 200:
                    break
        except Exception:
            time.sleep(3)
    else:
        raise RuntimeError('video_runtime_start_failed')
    health(23341)
    code, _, _ = request(23410, '/internal/v1/video-generation-deployment', 'POST',
                         {'enabled': True, 'auditId': NAME})
    assert code == 200, 'video_enable_failed'
    code, _, _ = request(23410, '/api/v1/execution-topology/clusters/' + CLUSTER_ID + '/nodes', 'POST',
                         {'nodeId': node['id'], 'weight': 1, 'priority': 0, 'enabled': True})
    assert code == 204, 'cluster_assignment_failed'
    with task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_definition WHERE id=%s AND enabled=TRUE", (TASK_ID,))
        assert cursor.fetchone()[0] == 1, 'task_definition_not_enabled'
    emit({'activated': True, 'gatewayExposed': False, 'modesEnabled': []})


def enable():
    data = values(ENV)
    if data.get('VIDEO_GENERATION_ROUTE_ENABLED') == 'true':
        emit({'enabled': True, 'changed': False})
        return
    data['VIDEO_GENERATION_ROUTE_ENABLED'] = 'true'
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    health(23200)
    run(['systemctl', 'enable', 'mytools-video-comfy', 'mytools-video-generation-service'])
    emit({'enabled': True, 'changed': True})


def set_mode_flags(enabled_modes):
    """按验收结果开关逐模式能力；服务与任务包两侧同时生效。"""
    data = values(ENV)
    unknown = [mode for mode in enabled_modes if mode not in MODE_FLAGS]
    assert not unknown, 'unknown_mode_' + ','.join(unknown)
    for mode, flag in MODE_FLAGS.items():
        data[flag] = 'true' if mode in enabled_modes else 'false'
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    write_executor_properties(data)
    # 执行器只在启动时读取 script-environments，因此开关变化必须重启它。
    run(['systemctl', 'restart', 'mytools-task-executor-service', 'mytools-video-generation-service'])
    health(23341)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            code, nodes, _ = request(23410, '/api/v1/execution-topology/nodes')
            if code == 200 and any(item['name'] == 'executor-remote-1' and item['status'] == 'ONLINE'
                                   for item in nodes):
                break
        except Exception:
            pass
        time.sleep(3)
    else:
        raise RuntimeError('executor_not_online_after_flag_change')
    return sorted(enabled_modes)


def edge_profile(path, frames):
    """量测成片左右补边：亮度、三通道均值与离散度、接近补边灰的连续列数。

    白边回归会让边缘亮度升到 0.9 以上；中性灰补边的亮度约 0.502。但只测亮度会漏掉
    "模型把补边改色"的缺陷（蓝边亮度 0.36、黄边约 0.6 都能过白边判据），因此这里同时
    解 RGB，报告每个通道的均值与最大通道差（spread），由调用方断言它确实是中性灰。
    """
    gray_filter = 'select=eq(n\\,{index}),format=gray'
    rgb_filter = 'select=eq(n\\,{index})'
    reports = []
    for index in frames:
        gray = run(['ffmpeg', '-v', 'error', '-i', str(path), '-vf', gray_filter.format(index=index),
                    '-frames:v', '1', '-f', 'rawvideo', '-'], timeout=120)
        if len(gray) != 832 * 480:
            raise RuntimeError('frame_decode_failed ' + str(len(gray)))
        rgb = run(['ffmpeg', '-v', 'error', '-i', str(path), '-vf', rgb_filter.format(index=index),
                   '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], timeout=120)
        if len(rgb) != 832 * 480 * 3:
            raise RuntimeError('frame_decode_failed_rgb ' + str(len(rgb)))
        columns = [sum(gray[row * 832 + column] for row in range(480)) / 480 / 255.0
                   for column in range(832)]

        def edge_channels(column_index):
            totals = [0, 0, 0]
            for row in range(480):
                offset = (row * 832 + column_index) * 3
                for channel in range(3):
                    totals[channel] += rgb[offset + channel]
            means = [total / 480 / 255.0 for total in totals]
            return [round(value, 4) for value in means], round(max(means) - min(means), 4)

        left_means, left_spread = edge_channels(0)
        right_means, right_spread = edge_channels(831)
        bars_left = 0
        for value in columns:
            if abs(value - 0.502) > EDGE_BRIGHTNESS_BAND:
                break
            bars_left += 1
        bars_right = 0
        for value in reversed(columns):
            if abs(value - 0.502) > EDGE_BRIGHTNESS_BAND:
                break
            bars_right += 1
        reports.append({'frame': index,
                        'leftMean': round(columns[0], 4), 'rightMean': round(columns[-1], 4),
                        'leftChannels': left_means, 'rightChannels': right_means,
                        'leftSpread': left_spread, 'rightSpread': right_spread,
                        'greyBarsLeft': bars_left, 'greyBarsRight': bars_right})
    return reports


def wait_job(job_id, timeout=2400):
    deadline = time.monotonic() + timeout
    terminal = ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'PARTIAL_SUCCESS')
    while time.monotonic() < deadline:
        code, result, _ = request(23341, '/internal/v1/videos/jobs/' + job_id)
        if code == 200 and result.get('status') in terminal:
            return result
        time.sleep(10)
    raise RuntimeError('job_timeout')


def verify():
    """维护窗口内跑一次真实的单图首帧任务，并检查白边缺陷是否真的消失。

    窗口内暂停含 GPU 步骤的任务定义并排空在途任务，结束后无论如何都恢复原状；
    这一步是唯一能把"已修好白边"变成证据的动作，未通过就不打开 FIRST_FRAME。
    """
    image = next((Path(item) for item in VERIFY_INPUT_CANDIDATES if Path(item).is_file()), None)
    assert image is not None, 'subject_image_missing'
    assert hashlib.sha256(image.read_bytes()).hexdigest() == VERIFY_INPUT_SHA256, 'subject_image_changed'
    with task_connection() as db, db.cursor() as cursor:
        cursor.execute('SELECT DISTINCT td.id,td.name,td.max_concurrency FROM task_definition td '
                       'JOIN task_step_definition ts ON ts.task_definition_id=td.id '
                       'WHERE ts.script_package IN (%s,%s) FOR UPDATE', GPU_PACKAGES)
        definitions = [{'id': row[0], 'name': row[1], 'capacity': row[2]} for row in cursor.fetchall()]
        assert definitions and all(row['capacity'] > 0 for row in definitions), 'gpu_capacity_unexpected'
        state = {'definitions': definitions, 'restored': False, 'startedAt': time.time()}
        write(STATE / 'verify-window.json', json.dumps(state))
        for row in definitions:
            cursor.execute('UPDATE task_definition SET max_concurrency=0 WHERE id=%s', (row['id'],))
        db.commit()
        deadline = time.monotonic() + 900
        ids = [row['id'] for row in definitions]
        placeholders = ','.join(['%s'] * len(ids))
        while time.monotonic() < deadline:
            with db.cursor() as check:
                check.execute(f"SELECT COUNT(*) FROM task_instance WHERE task_definition_id IN ({placeholders}) "
                              "AND status IN ('RUNNING','CANCELLING','QUEUED')", ids)
                if check.fetchone()[0] == 0:
                    break
            time.sleep(5)
        else:
            raise RuntimeError('production_gpu_tasks_active')
    # 能力开关既是"对用户开放"的闸门也是执行器的准入条件，因此复验期间必须先打开候选模式；
    # 网关路由仍然关闭，用户无法在这个窗口里提交任何任务。
    set_mode_flags(['FIRST_FRAME'])
    try:
        upload_id = None
        payload = image.read_bytes()
        req = urllib.request.Request('http://127.0.0.1:23341/internal/v1/videos/uploads/image', data=payload,
                                     headers={'Authorization': 'Bearer ' + values(ENV)['VIDEO_GENERATION_INTERNAL_TOKEN'],
                                              'X-Owner-Id': '1', 'Content-Type': 'image/png'}, method='POST')
        with urllib.request.urlopen(req, timeout=120) as reply:
            upload_id = json.loads(reply.read())['id']
        code, created, _ = request(23341, '/internal/v1/videos/jobs', 'POST', {
            'resourceId': 'wan-vace-1.3b-local', 'mode': 'FIRST_FRAME',
            'prompt': 'The subject in the frame begins to move gently. The camera slowly pushes in. '
                      'The opening composition and colours stay unchanged.',
            'inputs': [{'uploadId': upload_id, 'role': 'FIRST_FRAME'}],
            'output': {'size': '832x480', 'frames': 49, 'fps': 16}, 'seed': 42,
            'idempotencyKey': 'verify-' + str(int(time.time()))})
        assert code == 200, 'job_create_failed http=' + str(code) + ' body=' + json.dumps(created)[:300]
        result = wait_job(created['id'])
        assert result['status'] == 'SUCCEEDED', 'job_failed_' + str(result.get('errorCode'))
        code, payload, _ = request(23341, '/internal/v1/videos/jobs/' + created['id'] + '/video')
        assert code == 200, 'video_download_failed'
        target = STATE / 'verify-output.mp4'
        target.write_bytes(payload)
        edges = edge_profile(target, (0, 24, 48))
        assert all(max(item['leftMean'], item['rightMean']) < WHITE_EDGE_LIMIT for item in edges), \
            'white_edge_regression'
        # 补边还必须是中性灰：模型会把这两条边改成任意颜色，只判"不白"会漏。
        for item in edges:
            for side in ('left', 'right'):
                assert item[side + 'Spread'] <= EDGE_CHANNEL_SPREAD_LIMIT / 255.0, 'edge_not_neutral_' + side
                assert all(abs(value - 0.502) <= EDGE_BRIGHTNESS_BAND for value in item[side + 'Channels']), \
                    'edge_brightness_off_' + side
        emit({'verified': True, 'jobId': created['id'], 'edges': edges, 'enabledModes': ['FIRST_FRAME']})
    except BaseException:
        set_mode_flags([])
        raise
    finally:
        with task_connection() as db, db.cursor() as cursor:
            for row in definitions:
                cursor.execute('UPDATE task_definition SET max_concurrency=%s WHERE id=%s AND max_concurrency=0',
                               (row['capacity'], row['id']))
            db.commit()
        state = json.loads((STATE / 'verify-window.json').read_text())
        state['restored'] = True
        state['restoredAt'] = time.time()
        write(STATE / 'verify-window.json', json.dumps(state))


def rollback():
    # 保留数据库、已产出视频与历史包目录；先停路由再回退服务。
    code, _, _ = request(23410, '/internal/v1/video-generation-deployment', 'POST',
                         {'enabled': False, 'auditId': NAME + '-rollback'})
    assert code == 200, 'disable_before_rollback_failed'
    with task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'rollback_wait_for_tasks'
    data = values(ENV)
    data.update(VIDEO_GENERATION_ROUTE_ENABLED='false', VIDEO_LOCAL_VALIDATED='false',
                VIDEO_GPU_COORDINATION_VALIDATED='false')
    for flag in MODE_FLAGS.values():
        data[flag] = 'false'
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    for service in SERVICES:
        for name in ('zzzzz-video-generation.conf', 'zz-video-generation.conf'):
            path = UNITS / ('mytools-' + service + '.service.d/' + name)
            if path.exists():
                path.unlink()
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'restart', 'mytools-task-scheduler-service', 'mytools-task-executor-service',
         'mytools-mytools-gateway'])
    run(['systemctl', 'stop', 'mytools-video-comfy', 'mytools-video-generation-service'])
    health(23410)
    health(23200)
    emit({'rolledBack': True, 'videosRetained': True, 'packageRetained': True})


COMMANDS = {'stage': stage, 'activate': activate, 'enable': enable, 'verify': verify, 'rollback': rollback}


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=sorted(COMMANDS))
    parsed = parser.parse_args()
    try:
        assert os.geteuid() == 0, 'root_required'
        COMMANDS[parsed.command]()
    except Exception as error:
        import traceback
        emit({'failed': True, 'type': type(error).__name__,
              'line': traceback.extract_tb(error.__traceback__)[-1].lineno,
              'reason': str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'private_diagnostic_suppressed'})
        sys.exit(1)
