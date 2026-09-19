"""与管理员固定地址的本机 ComfyUI 通信；不接受客户端地址、不跟随重定向。

参考图、控制视频与掩码都通过 `/upload/image` 上传到输入目录的**扁平文件名**上：该端点
接收任意扩展名的文件，而 `LoadImage`/`LoadVideo` 的下拉选项由每次校验时重新扫描输入目录
生成，因此扁平文件名才能立刻通过校验；子目录不会被 `os.listdir` 列出。
"""

import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_JSON = 2 * 1024 * 1024
MAX_BINARY = 256 * 1024 * 1024


def base_url():
    """读取受管视频运行时地址，默认本机 8190。"""
    return os.environ.get('VIDEO_COMFY_URL', 'http://127.0.0.1:8190')


def check_base(base):
    """只允许本机 http 且不带任何路径、查询或凭据。"""
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1') or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('VIDEO_ENDPOINT_INVALID')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """拒绝重定向，避免任务数据流向其他端点。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('VIDEO_REDIRECT_REJECTED')


def call(base, path, body=None, binary=False, timeout=30):
    """向固定 Comfy 端点发起有界请求。"""
    check_base(base)
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base.rstrip('/') + path, data=data,
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout) as response:
        limit = MAX_BINARY if binary else MAX_JSON
        value = response.read(limit + 1)
        if len(value) > limit:
            raise ValueError('VIDEO_RESPONSE_TOO_LARGE')
        return value if binary else json.loads(value)


def upload_file(base, path, name):
    """把受管文件上传到 Comfy 输入目录并返回可绑定的扁平文件名。"""
    check_base(base)
    if not name or Path(name).name != name or '..' in name or '/' in name or '\\' in name:
        raise ValueError('VIDEO_UPLOAD_NAME_INVALID')
    value = Path(path).read_bytes()
    if not 0 < len(value) <= MAX_BINARY:
        raise ValueError('VIDEO_UPLOAD_INVALID')
    boundary = 'mytools-' + uuid.uuid4().hex
    data = bytearray()
    for key, text in [('type', 'input'), ('subfolder', ''), ('overwrite', 'true')]:
        data.extend(('--' + boundary + '\r\nContent-Disposition: form-data; name="' + key
                     + '"\r\n\r\n' + text + '\r\n').encode())
    data.extend(('--' + boundary + '\r\nContent-Disposition: form-data; name="image"; filename="' + name
                 + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode())
    data.extend(value)
    data.extend(('\r\n--' + boundary + '--\r\n').encode())
    request = urllib.request.Request(base.rstrip('/') + '/upload/image', data=bytes(data),
                                     headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=300) as response:
        raw = response.read(65537)
    if len(raw) > 65536:
        raise ValueError('VIDEO_RESPONSE_TOO_LARGE')
    reply = json.loads(raw)
    # 只接受与请求完全一致的落盘结果，避免 Comfy 改名后绑定到错误的文件。
    if reply.get('name') != name or reply.get('type') != 'input' or reply.get('subfolder') not in ('', None):
        raise ValueError('VIDEO_UPLOAD_REJECTED')
    return name


def submit(base, graph, client_id):
    """提交固定工作流并返回 prompt_id；响应缺失即视为不确定，不做二次提交。"""
    reply = call(base, '/prompt', {'prompt': graph, 'client_id': client_id}, timeout=60)
    prompt_id = reply.get('prompt_id')
    if not isinstance(prompt_id, str):
        raise ValueError('VIDEO_SUBMISSION_UNCERTAIN')
    return str(uuid.UUID(prompt_id))
