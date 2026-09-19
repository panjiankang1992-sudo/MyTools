#!/usr/bin/env python3
"""固定工作流图片执行器；模型验收前拒绝运行，任何失败均不切换供应方。"""
from __future__ import annotations
import base64
import copy
import hashlib
import json
import os
import signal
from pathlib import Path
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from gpu_lease import GpuLease

MAX_IMAGE = 5 * 1024 * 1024

class NoRedirect(urllib.request.HTTPRedirectHandler):
    """拒绝重定向，避免凭据及任务数据流向其他端点。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("IMAGE_REDIRECT_REJECTED")


def call(base, path, body=None, binary=False):
    """向管理员固定的本机 ComfyUI 发起有界请求。"""
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1') or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('IMAGE_ENDPOINT_INVALID')
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base.rstrip('/') + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
        limit = MAX_IMAGE if binary else 2 * 1024 * 1024
        value = response.read(limit + 1)
        if len(value) > limit:
            raise ValueError('IMAGE_RESPONSE_TOO_LARGE')
        return value if binary else json.loads(value)


def png(value):
    """校验 PNG 签名和有界像素；业务服务还会完整解码。"""
    if len(value) < 24 or len(value) > MAX_IMAGE or value[:8] != b'\x89PNG\r\n\x1a\n' or value[12:16] != b'IHDR':
        raise ValueError('IMAGE_OUTPUT_INVALID')
    width, height = struct.unpack('>II', value[16:24])
    if min(width, height) < 1 or width * height > 16000000:
        raise ValueError('IMAGE_OUTPUT_INVALID')
    return value


def child(root, *parts):
    """阻止受管目录中的符号链接改变用户文件归属。"""
    path = root
    for part in parts:
        path = path / part
        if path.is_symlink():
            raise ValueError('IMAGE_PATH_INVALID')
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('IMAGE_PATH_INVALID')
    return path


def workflow(parameters):
    """只读取由管理员验收并固定摘要的工作流，不接受客户端工作流节点。"""
    if os.environ.get('IMAGE_GENERATION_LOCAL_VALIDATED') != 'true' or os.environ.get('IMAGE_GPU_COORDINATION_VALIDATED') != 'true':
        raise ValueError('IMAGE_LOCAL_VALIDATION_REQUIRED')
    if parameters.get('resourceId') != 'krea2-local':
        raise ValueError('IMAGE_PROVIDER_VALIDATION_REQUIRED')
    raw = Path(os.environ['IMAGE_WORKFLOW_FILE']).read_bytes()
    expected = os.environ.get('IMAGE_WORKFLOW_SHA256', '')
    if not expected or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('IMAGE_WORKFLOW_DIGEST_MISMATCH')
    spec = json.loads(raw)
    if spec['revision'] != parameters['workflowRevision'] or parameters['mode'] not in spec['modes']:
        raise ValueError('IMAGE_WORKFLOW_UNSUPPORTED')
    if not spec.get('allowedClassTypes') or any(node['class_type'] not in spec['allowedClassTypes'] for node in spec['graph'].values()):
        raise ValueError('IMAGE_WORKFLOW_NODE_REJECTED')
    return spec


def bind(spec, values):
    """通过验收清单的精确节点输入绑定值，禁止文本模板插值。"""
    graph = copy.deepcopy(spec['graph'])
    for name, value in values.items():
        bindings = spec['bindings'].get(name)
        if not bindings:
            raise ValueError('IMAGE_WORKFLOW_BINDING_MISSING')
        for node, field in bindings:
            if field not in graph[node]['inputs']:
                raise ValueError('IMAGE_WORKFLOW_BINDING_INVALID')
            graph[node]['inputs'][field] = value
    return graph


class PromptCleanup:
    """取消时仅中断当前租约提交的任务，不能影响其他 Comfy 工作。"""
    def __init__(self, base):
        self.base = base
        self.prompt_id = None
        self.previous = None
    def __enter__(self):
        self.previous = signal.signal(signal.SIGTERM, self.terminate)
        return self
    def terminate(self, *args):
        raise SystemExit(143)
    def __exit__(self, *args):
        signal.signal(signal.SIGTERM, self.previous)
        try:
            queue = call(self.base, '/queue')
            running = [item[1] for item in queue.get('queue_running', [])]
            pending = [item[1] for item in queue.get('queue_pending', [])]
            if self.prompt_id in pending:
                call(self.base, '/queue', {'delete': [self.prompt_id]})
            if self.prompt_id is not None and running == [self.prompt_id]:
                call(self.base, '/interrupt', {})
            if not running and not pending:
                call(self.base, '/free', {'unload_models': True, 'free_memory': True})
        except Exception:
            # GPU 租约入口会拒绝仍在运行的队列，异常不会绕过下次检查。
            pass


def generate(parameters):
    """串行生成，持久记录 prompt_id；进程重启时只对账已有提交。"""
    if parameters.get("resourceId") == "sillytraven-remote":
        return remote_generate(parameters)
    spec = workflow(parameters)
    job = str(uuid.UUID(parameters['jobId']))
    if parameters['count'] not in (1, 2, 4) or parameters['size'] not in ('1024x1024', '832x1216', '1216x832'):
        raise ValueError('IMAGE_INPUT_INVALID')
    if not isinstance(parameters['prompt'], str) or not 1 <= len(parameters['prompt'].strip()) <= 4000:
        raise ValueError('IMAGE_INPUT_INVALID')
    root = Path(os.environ['IMAGE_GENERATION_ROOT']).resolve(strict=True)
    output = child(root, 'outputs', job)
    output.mkdir(parents=True, exist_ok=True)
    width, height = map(int, parameters['size'].split('x'))
    base = os.environ.get('IMAGE_COMFY_URL', 'http://127.0.0.1:8188')
    references = parameters.get('references', [])
    values = {'prompt': parameters['prompt'], 'width': width, 'height': height}
    if parameters['mode'] == 'TEXT_TO_IMAGE' and references:
        raise ValueError('IMAGE_INPUT_INVALID')
    if parameters['mode'] == 'STYLE_REFERENCE':
        if not 1 <= len(references) <= 2:
            raise ValueError('IMAGE_INPUT_INVALID')
        # 验收工作流使用共享受管目录读取器；路径只能来自已签发 UUID。
        for index, reference in enumerate(references):
            path = child(root, 'inputs', str(uuid.UUID(reference)))
            if not path.is_file() or path.stat().st_size > MAX_IMAGE:
                raise ValueError('IMAGE_REFERENCE_INVALID')
            values['reference' + str(index)] = str(path)
    completed = []
    # 锁必须与标签模型共用；部署门禁确认后才能启用图像模型。
    for index in range(parameters['count']):
        # 每张图片释放租约，允许标签任务在多图请求之间获得显存。
        with GpuLease("image"), PromptCleanup(base) as cleanup:
            target = child(root, 'outputs', job, str(index) + '.png')
            if target.exists():
                png(target.read_bytes())
                completed.append(index)
                continue
            state_file = child(root, 'outputs', job, str(index) + '.submission.json')
            values['seed'] = (int(parameters['seed']) + index) % 2147483648
            if state_file.exists():
                state = json.loads(state_file.read_text())
                if not state.get('promptId'):
                    # 提交响应丢失后停止，不能猜测失败并重复生成。
                    raise ValueError('IMAGE_SUBMISSION_UNCERTAIN')
                prompt_id = state['promptId']
            else:
                state_file.write_text(json.dumps({'submissionStarted': True}))
                response = call(base, '/prompt', {'prompt': bind(spec, values), 'client_id': job})
                prompt_id = str(uuid.UUID(response['prompt_id']))
                state_file.write_text(json.dumps({'promptId': prompt_id}))
            cleanup.prompt_id = prompt_id
            deadline = time.monotonic() + 600
            while True:
                history = call(base, '/history/' + prompt_id).get(prompt_id)
                if history:
                    if history.get('status', {}).get('status_str') == 'error':
                        raise ValueError('IMAGE_INFERENCE_FAILED')
                    if history.get('status', {}).get('completed'):
                        break
                if time.monotonic() > deadline:
                    # 不调用全局 interrupt；超时任务由受管 ComfyUI 停止后才允许下一任务。
                    raise TimeoutError('IMAGE_INFERENCE_TIMEOUT')
                time.sleep(2)
            images = history['outputs'][spec['outputNode']]['images']
            if len(images) != 1 or images[0].get('type') != 'output':
                raise ValueError('IMAGE_OUTPUT_INVALID')
            item = images[0]
            filename = item['filename']
            subfolder = item.get('subfolder', '')
            if Path(filename).name != filename or '\\' in filename or subfolder.startswith('/') or '..' in subfolder.split('/') or '\\' in subfolder:
                raise ValueError('IMAGE_OUTPUT_PATH_INVALID')
            value = png(call(base, '/view?' + urllib.parse.urlencode({'filename': filename, 'subfolder': subfolder, 'type': 'output'}), binary=True))
            temporary = child(root, 'outputs', job, str(index) + '.tmp')
            with temporary.open('xb') as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(target)
            completed.append(index)
    return {'indices': completed, 'workflowRevision': spec['revision']}


def remote_generate(parameters):
    """已验收的 OpenAI Images 协议适配器；403、超时和响应歧义均不自动重试。"""
    if os.environ.get('IMAGE_REMOTE_VALIDATED') != 'true' or parameters.get('mode') != 'TEXT_TO_IMAGE' or parameters.get('references'):
        raise ValueError('IMAGE_PROVIDER_VALIDATION_REQUIRED')
    model = os.environ.get('IMAGE_REMOTE_MODEL', '')
    if not model or parameters.get('modelId') != model or parameters.get('size') != '1024x1024' or parameters.get('count') not in (1, 2, 4):
        raise ValueError('IMAGE_INPUT_INVALID')
    base = os.environ.get('IMAGE_REMOTE_URL', 'https://api.sillytraven.dev/api/ai/v1/')
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'https' or parsed.username or parsed.query or parsed.fragment:
        raise ValueError('IMAGE_ENDPOINT_INVALID')
    # 复用管理员映射的现有资源密钥文件，不把密钥放入任务参数。
    secret = Path(os.environ['IMAGE_REMOTE_KEY_FILE']).read_text().strip()
    if not secret or len(secret) > 8192 or any(character.isspace() for character in secret):
        raise ValueError('IMAGE_CREDENTIAL_INVALID')
    root = Path(os.environ['IMAGE_GENERATION_ROOT']).resolve(strict=True)
    job = str(uuid.UUID(parameters['jobId']))
    output = child(root, 'outputs', job)
    output.mkdir(parents=True, exist_ok=True)
    completed = []
    for index in range(parameters['count']):
        target = child(root, 'outputs', job, str(index) + '.png')
        if target.exists():
            png(target.read_bytes())
            completed.append(index)
            continue
        marker = child(root, 'outputs', job, str(index) + '.submission.json')
        # 请求结果未知时禁止再次付费提交，要求运维对账。
        with marker.open('x') as stream:
            json.dump({'submissionStarted': True}, stream)
        body = {'model': model, 'prompt': parameters['prompt'], 'size': '1024x1024', 'n': 1, 'response_format': 'b64_json'}
        request = urllib.request.Request(base.rstrip('/') + '/images/generations', data=json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + secret})
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=240) as response:
                raw = response.read(8 * 1024 * 1024 + 1)
        except urllib.error.HTTPError as error:
            if error.code in (400, 401, 403, 404, 405, 409, 413, 415, 422, 429):
                marker.write_text(json.dumps({'submissionStarted': True, 'rejected': True}))
            raise
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError('IMAGE_RESPONSE_TOO_LARGE')
        data = json.loads(raw)['data']
        if len(data) != 1 or not isinstance(data[0].get('b64_json'), str):
            raise ValueError('IMAGE_RESPONSE_INVALID')
        # 不抓取供应方返回的任意 URL；上线验收固定为内联 PNG。
        value = png(base64.b64decode(data[0]['b64_json'], validate=True))
        temporary = child(root, 'outputs', job, str(index) + '.tmp')
        with temporary.open('xb') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
        completed.append(index)
    return {'indices': completed, 'workflowRevision': 'remote-images-v1'}


def main():
    """执行已签发任务，并仅输出稳定错误码。"""
    try:
        context = json.loads(Path(os.environ['TASK_CONTEXT_FILE']).read_text())
        result = generate(context['parameters'])
        Path(os.environ['TASK_RESULT_FILE']).write_text(json.dumps(result))
    except Exception:
        from mytools_task_sdk.errors import write_task_error
        write_task_error('IMAGE_EXECUTION_FAILED', 'PERMANENT', 'IMAGE_EXECUTION_FAILED')
        raise SystemExit(1)

if __name__ == '__main__':
    main()
