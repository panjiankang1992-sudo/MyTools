"""供图片与标签包共用的显存互斥协议，异常遗留的 Comfy 队列同样阻止标签加载。"""
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.parse
import urllib.request


def request(base, path, body=None):
    """只允许本机端点，不跟随重定向。"""
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1') or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('GPU_ENDPOINT_INVALID')
    class RejectRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise ValueError('GPU_REDIRECT_REJECTED')
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base.rstrip('/') + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(RejectRedirect()).open(req, timeout=20) as response:
        raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ValueError('GPU_RESPONSE_TOO_LARGE')
        return json.loads(raw) if raw else {}


def wait_idle(base):
    """等待先前被取消的任务结束，始终不加载第二个 GPU 模型。"""
    deadline = time.monotonic() + 60
    while True:
        queue = request(base, '/queue')
        if not queue.get('queue_running') and not queue.get('queue_pending'):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('GPU_COMFY_BUSY')
        time.sleep(1)


def acquire(handle):
    """有界阻塞等待锁，避免轮询错过图片之间的释放窗口。"""
    def expired(*args):
        raise TimeoutError('GPU_BUSY')
    previous = signal.signal(signal.SIGALRM, expired)
    started = time.monotonic()
    timer = signal.setitimer(signal.ITIMER_REAL, 120)
    try:
        fcntl.flock(handle, fcntl.LOCK_EX)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
        if timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL, max(0.001, timer[0] - (time.monotonic() - started)), timer[1])


def wait_free(minimum_mib):
    """轮询驱动而非仅依赖卸载请求响应，目标机固定为单 GPU。"""
    deadline = time.monotonic() + 30
    while True:
        result = subprocess.run(['/usr/bin/nvidia-smi', '--query-gpu=memory.free', '--format=csv,noheader,nounits'],
                                capture_output=True, text=True, timeout=5, check=True)
        rows = result.stdout.strip().splitlines()
        if len(rows) != 1 or not rows[0].strip().isdigit():
            raise ValueError('GPU_RESOURCE_UNVERIFIED')
        if int(rows[0].strip()) >= minimum_mib:
            return
        if time.monotonic() >= deadline:
            raise TimeoutError('GPU_MEMORY_UNAVAILABLE')
        time.sleep(1)


class GpuLease:
    """锁覆盖模型调用；门禁关闭时标签行为保持原样。"""
    def __init__(self, kind):
        self.kind = kind
        self.handle = None

    def __enter__(self):
        if os.environ.get('IMAGE_GPU_COORDINATION_VALIDATED') != 'true':
            if self.kind == 'image':
                raise ValueError('GPU_COORDINATION_REQUIRED')
            return self
        path = Path(os.environ['IMAGE_GPU_LOCK_FILE'])
        if path.is_symlink():
            raise ValueError('GPU_LOCK_INVALID')
        self.handle = path.open('a')
        try:
            acquire(self.handle)
            comfy = os.environ.get('IMAGE_COMFY_URL', 'http://127.0.0.1:8188')
            # 取消后的推理可能仍在结束，先等待其排空，避免使后续正常任务失败。
            wait_idle(comfy)
            request(comfy, '/free', {'unload_models': True, 'free_memory': True})
            if self.kind == 'image':
                ollama = os.environ.get('TAGGING_SERVICE_URL', 'http://127.0.0.1:11434')
                models = request(ollama, '/api/ps').get('models', [])
                allowed = os.environ.get('TAGGING_MODEL', 'huihui_ai/qwen3-vl-abliterated:8b')
                # 只卸载共用此租约的标签模型，遇到其他模型即停止。
                if any(item.get('name') != allowed for item in models):
                    raise RuntimeError('GPU_OTHER_MODEL_RESIDENT')
                if models:
                    request(ollama, '/api/generate', {'model': allowed, 'keep_alive': 0})
                if request(ollama, '/api/ps').get('models'):
                    raise ValueError('GPU_MODEL_UNLOAD_PENDING')
            # Comfy 的卸载请求是异步的；确认驱动显存已释放后才允许切换模型。
            wait_free(10 * 1024 if self.kind == 'tag' else 12 * 1024)
            return self
        except BaseException:
            self.handle.close()
            self.handle = None
            raise

    def __exit__(self, *args):
        if self.handle is not None:
            self.handle.close()
            self.handle = None
