"""通过受管任务执行离线 PDF 运算并发布经过验证的产物。"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import signal
import urllib.error
import urllib.request
from uuid import UUID
from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.storage import StorageGatewayClient
from mytools_task_sdk.asset import AssetRegistryClient

MAX_BYTES = 100 * 1024 * 1024


def digest(path: Path) -> str:
    """流式计算文件摘要。"""
    result = hashlib.sha256()
    with path.open('rb') as handle:
        while chunk := handle.read(65536):
            result.update(chunk)
    return result.hexdigest()


def sandbox(command: list[str], work: Path, package: Path) -> list[str]:
    """生产计算进程仅能读取运行时和任务目录，无网络及宿主凭据。"""
    if sys.platform != 'linux':
        if os.getenv('PDF_ALLOW_UNSANDBOXED_TEST', 'false') != 'true':
            raise RuntimeError('PDF_009')
        return command
    bwrap = shutil.which('bwrap')
    if not bwrap:
        raise RuntimeError('PDF_009')
    args = [bwrap, '--unshare-all', '--die-with-parent', '--new-session', '--proc', '/proc',
            '--dev', '/dev', '--tmpfs', '/tmp', '--dir', '/etc', '--dir', '/home']
    paths = ['/usr', '/lib', '/lib64', '/etc/ld.so.cache', '/etc/fonts', sys.prefix, sys.base_prefix, os.getenv('PDF_FONT_FILE', '/nonexistent'),
             os.getenv('PDF_JAVA_HOME', '/usr/lib/jvm'), os.getenv('PLAYWRIGHT_BROWSERS_PATH', '/opt/pdf-browsers'), str(package)]
    # Debian 的 JDK 配置经符号链接放在独立目录，需只读挂载以初始化安全提供程序。
    paths.extend(str(path) for path in Path('/etc').glob('java-*-openjdk') if path.is_dir())
    for path in sorted(set(paths)):
        if Path(path).exists():
            args += ['--ro-bind', path, path]
    args += ['--bind', str(work), str(work), '--chdir', str(work), '--']
    return args + command


def run(command: list[str], work: Path, package: Path) -> None:
    """仅将白名单运行参数传给离线子进程，秘密不继承。"""
    environment = {'PATH': '/usr/bin:/bin', 'HOME': str(work), 'LANG': 'C.UTF-8', 'TMPDIR': '/tmp'}
    for key in ('PLAYWRIGHT_BROWSERS_PATH', 'PDF_FONT_FILE'):
        if os.getenv(key):
            environment[key] = os.environ[key]
    process = subprocess.Popen(sandbox(command, work, package), start_new_session=True,
                               env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + 240
    try:
        while process.poll() is None:
            if time.monotonic() > deadline:
                raise subprocess.TimeoutExpired(command, 240)
            size = sum(path.stat().st_size for path in work.rglob('*') if path.is_file())
            if size > 650 * 1024 * 1024:
                raise subprocess.CalledProcessError(1, command)
            time.sleep(.25)
        if process.returncode:
            raise subprocess.CalledProcessError(process.returncode, command)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def main() -> None:
    """领取冻结输入，执行版本化内核并幂等发布。"""
    context = TaskContext.load()
    work_id = str(UUID(context.parameters['workId']))
    base = os.environ['PDF_SERVICE_URL'].rstrip('/') + '/internal/v1/pdf'
    headers = {'Authorization': 'Bearer ' + os.environ['PDF_WORKER_TOKEN'],
               'X-Task-Instance-Id': str(context.context['taskInstanceId']),
               'X-Task-Fencing-Token': str(context.context['fencingToken'])}

    def call(path: str, body: dict) -> dict:
        request = urllib.request.Request(base + path, json.dumps(body).encode(),
                                         headers={**headers, 'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    # 调度器创建成功与领域记录回写之间存在很短的窗口，有限重试等待登记。
    for attempt in range(6):
        try:
            request = call(f'/work/{work_id}/claim', {})
            break
        except urllib.error.HTTPError as error:
            if error.code != 409 or attempt == 5:
                raise
            time.sleep(1)
    package = Path(__file__).resolve().parents[1]
    work = Path.cwd().resolve() / ('pdf-' + work_id)
    work.mkdir(exist_ok=True)
    output = work / 'output'
    output.mkdir(exist_ok=True)
    # 重试前不复用未验证输出，目录仅由任务 UUID 构造。
    for old in output.iterdir():
        if old.is_file():
            old.unlink()
    inputs = []
    total = 0
    attachment_paths = {}
    main_ids = request.get('inputIds', [])
    for index, input_id in enumerate(main_ids + request.get('attachmentIds', [])):
        input_id = str(UUID(input_id))
        path = work / f'input-{index}'
        with urllib.request.urlopen(urllib.request.Request(base + f'/work/{work_id}/inputs/{input_id}/content', headers=headers), timeout=60) as response, path.open('wb') as target:
            while chunk := response.read(65536):
                total += len(chunk)
                if total > MAX_BYTES:
                    raise ValueError('PDF_003')
                target.write(chunk)
        refs = request.get('resolvedInputs', [])
        expected = refs[index]['sha256'] if refs else request.get('sha256')
        if expected and digest(path) != expected:
            raise ValueError('PDF_010')
        if index < len(main_ids):
            inputs.append(str(path))
        else:
            attachment_paths[input_id] = {'path': str(path), 'mediaType': refs[index]['mediaType']}
    request['attachments'] = attachment_paths
    request['inputPaths'] = inputs
    request_path = work / 'request.json'
    request_path.write_text(json.dumps(request), encoding='utf-8')
    jar = package / 'lib' / 'pdf-worker.jar'
    expected_jar = (package / 'lib' / 'pdf-worker.sha256').read_text().strip()
    if digest(jar) != expected_jar:
        raise RuntimeError('PDF_010')
    java = os.getenv('PDF_JAVA_BINARY', '/usr/bin/java')
    try:
        if request['operation'] in ('TEXT_TO_PDF', 'MARKDOWN_TO_PDF'):
            run([sys.executable, str(package / 'scripts' / 'render_text.py'), str(request_path), str(output)], work, package)
            # 浏览器生成产物仍交由 PDFBox 校验和生成预览，不把浏览器退出码当成功。
            check = {**request, 'operation': 'PDF_SPLIT', 'inputPaths': [str(output / 'result.pdf')], 'options': {'pagesPerFile': 1000}}
            check_path = work / 'check.json'
            check_path.write_text(json.dumps(check))
            checked = work / 'checked'
            if checked.exists():
                shutil.rmtree(checked)
            checked.mkdir()
            run([java, '-Xmx512m', '-jar', str(jar), str(check_path), str(checked)], work, package)
            shutil.rmtree(output)
            checked.rename(output)
        else:
            run([java, '-Xmx512m', '-jar', str(jar), str(request_path), str(output)], work, package)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        error_path = output / 'error.json'
        error = json.loads(error_path.read_text()).get('code') if error_path.exists() else 'PDF_009'
        call(f'/work/{work_id}/complete', {'errorCode': error})
        raise RuntimeError(error) from None
    result = json.loads((output / 'manifest.json').read_text())
    if request['operation'] in ('TEXT_TO_PDF', 'MARKDOWN_TO_PDF'):
        result['rendererVersion'] = 'playwright-1.63.0/template-1.0.0'
        result['fontSha256'] = digest(Path(os.environ['PDF_FONT_FILE']))
    storage = StorageGatewayClient(os.environ['STORAGE_GATEWAY_URL'], os.environ['STORAGE_INTERNAL_TOKEN'])
    registry = AssetRegistryClient(os.environ['ASSET_REGISTRY_URL'], os.environ['ASSET_REGISTRY_INTERNAL_TOKEN'])
    total_output = 0
    for field in ('artifacts', 'previews'):
        for index, item in enumerate(result.get(field, [])):
            filename = item['filename']
            path = output / filename
            if path.parent != output or not path.is_file() or path.is_symlink():
                raise ValueError('PDF_010')
            size = path.stat().st_size
            total_output += size
            if total_output > 500 * 1024 * 1024:
                raise ValueError('PDF_003')
            sha = digest(path)
            key = f'pdf:{work_id}:{field}:{index}:{sha}'
            uri = storage.publish(path, os.getenv('PDF_STORAGE_ROOT', 'managed'), f'pdf/{work_id}/{filename}', key, size, sha)
            asset = registry.register({'ownerId': int(request['ownerId']), 'idempotencyKey': key,
                'sourceType': 'PDF_TOOL', 'sourceBusinessId': f'{work_id}:{field}:{index}',
                'contentSha256': sha, 'sizeBytes': size, 'mimeType': item['mediaType'],
                'location': {'idempotencyKey': key + ':location', 'providerType': 'STORAGE_GATEWAY', 'storageUri': uri, 'providerVersion': '1'}})
            item.update(storageUri=uri, assetId=asset['id'], sha256=sha, bytes=size)
    # 显示名不参与内部存储路径，扩展名始终由实际产物类型决定。
    display_name = request.get('output', {}).get('name')
    if display_name:
        artifacts = result.get('artifacts', [])
        primary = artifacts[-1] if artifacts else None
        if primary:
            extension = Path(primary['filename']).suffix
            primary['filename'] = str(Path(display_name).with_suffix(extension))
    call(f'/work/{work_id}/complete', result)
    Path(os.environ['TASK_RESULT_FILE']).write_text(json.dumps({'workId': work_id, 'completed': True}))


if __name__ == '__main__':
    main()
