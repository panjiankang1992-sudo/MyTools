"""视频包使用的显存互斥协议；与图片、标签包共用同一把锁。

与图片包的唯一区别是这里必须**同时**清空两个 Comfy 进程的权重：视频运行时（8190）与本机图片
Comfy（8189）。视频 49 帧实测峰值约 14.5 GiB，本机只有 16311 MiB，任何一方残留权重都会让视频
推理在加载阶段就失败。释放顺序反过来也成立：退出前必须清空视频权重，否则后续标签任务会
因为可用显存不足而报 GPU_MEMORY_UNAVAILABLE。
"""

import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def request(base, path, body=None, timeout=20):
    """只允许本机端点，不跟随重定向。"""
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1') or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('GPU_ENDPOINT_INVALID')

    class RejectRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise ValueError('GPU_REDIRECT_REJECTED')

    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base.rstrip('/') + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(RejectRedirect()).open(req, timeout=timeout) as response:
        raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ValueError('GPU_RESPONSE_TOO_LARGE')
        return json.loads(raw) if raw else {}


def comfy_urls():
    """返回需要参与显存协调的 Comfy 端点：视频运行时与图片服务。"""
    video = os.environ.get('VIDEO_COMFY_URL', 'http://127.0.0.1:8190')
    image = os.environ.get('IMAGE_COMFY_URL', 'http://127.0.0.1:8189')
    return video, image


def best_effort(base, path, body=None):
    """对端未启动时不算失败；HTTP 层错误仍然向上抛出。"""
    try:
        return request(base, path, body)
    except urllib.error.HTTPError:
        raise
    except (urllib.error.URLError, OSError, TimeoutError):
        return None


def wait_idle(base):
    """等待先前被取消的任务结束，始终不加载第二个 GPU 模型。"""
    deadline = time.monotonic() + 60
    while True:
        queue = best_effort(base, '/queue')
        if queue is None or (not queue.get('queue_running') and not queue.get('queue_pending')):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('GPU_COMFY_BUSY')
        time.sleep(1)


def acquire(handle):
    """有界阻塞等待锁，避免轮询错过其他任务之间的释放窗口。

    视频推理 8–17 分钟，比图片长得多，因此等待上限放宽到 1800 秒；超时即放弃本次任务，
    不会在不持锁的情况下加载权重。
    """
    def expired(*args):
        raise TimeoutError('GPU_BUSY')
    previous = signal.signal(signal.SIGALRM, expired)
    started = time.monotonic()
    timer = signal.setitimer(signal.ITIMER_REAL, 1800)
    try:
        fcntl.flock(handle, fcntl.LOCK_EX)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
        if timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL, max(0.001, timer[0] - (time.monotonic() - started)), timer[1])


def nvidia_smi():
    """显存查询使用绝对路径；只有测试夹具会把它指向替身。"""
    return os.environ.get('VIDEO_NVIDIA_SMI', '/usr/bin/nvidia-smi')


def gpu_used_mib():
    """读取本机显存占用；单卡机器上只有一行输出。"""
    result = subprocess.run([nvidia_smi(), '--query-gpu=memory.used', '--format=csv,noheader,nounits'],
                            capture_output=True, text=True, timeout=5, check=True)
    rows = result.stdout.strip().splitlines()
    if len(rows) != 1 or not rows[0].strip().isdigit():
        raise ValueError('GPU_RESOURCE_UNVERIFIED')
    return int(rows[0].strip())


def wait_free(minimum_mib, timeout=90):
    """轮询驱动而非仅依赖卸载请求响应，目标机固定为单 GPU。"""
    deadline = time.monotonic() + timeout
    while True:
        result = subprocess.run([nvidia_smi(), '--query-gpu=memory.free', '--format=csv,noheader,nounits'],
                                capture_output=True, text=True, timeout=5, check=True)
        rows = result.stdout.strip().splitlines()
        if len(rows) != 1 or not rows[0].strip().isdigit():
            raise ValueError('GPU_RESOURCE_UNVERIFIED')
        if int(rows[0].strip()) >= minimum_mib:
            return int(rows[0].strip())
        if time.monotonic() >= deadline:
            raise TimeoutError('GPU_MEMORY_UNAVAILABLE')
        time.sleep(1)


def unload_ollama():
    """标签模型不共用 Comfy 的显存池，必须单独确认没有驻留。"""
    base = os.environ.get('TAGGING_SERVICE_URL', 'http://127.0.0.1:11434')
    allowed = os.environ.get('TAGGING_MODEL', 'huihui_ai/qwen3-vl-abliterated:8b')
    models = best_effort(base, '/api/ps')
    if models is None:
        return
    if any(item.get('name') != allowed for item in models.get('models', [])):
        raise RuntimeError('GPU_OTHER_MODEL_RESIDENT')
    if models.get('models'):
        request(base, '/api/generate', {'model': allowed, 'keep_alive': 0})


class GpuLease:
    """锁覆盖"加载权重到推理结束"的整段区间；门禁关闭时直接拒绝运行。"""

    def __init__(self, minimum_free_mib=None):
        self.minimum_free_mib = minimum_free_mib or int(os.environ.get('VIDEO_MIN_FREE_MIB', 14848))
        self.handle = None

    def __enter__(self):
        if os.environ.get('VIDEO_GPU_COORDINATION_VALIDATED') != 'true':
            raise ValueError('GPU_COORDINATION_REQUIRED')
        path = Path(os.environ['VIDEO_GPU_LOCK_FILE'])
        if path.is_symlink():
            raise ValueError('GPU_LOCK_INVALID')
        self.handle = path.open('a')
        try:
            acquire(self.handle)
            video, image = comfy_urls()
            # 取消后的推理可能仍在收尾，先把两个队列都排空再考虑加载权重。
            wait_idle(video)
            wait_idle(image)
            best_effort(video, '/free', {'unload_models': True, 'free_memory': True})
            best_effort(image, '/free', {'unload_models': True, 'free_memory': True})
            unload_ollama()
            # Comfy 的卸载请求是异步的；确认驱动显存已释放后才允许加载 14.5 GiB 的视频权重。
            self.free_mib = wait_free(self.minimum_free_mib)
            return self
        except BaseException:
            self.handle.close()
            self.handle = None
            raise

    def __exit__(self, *args):
        if self.handle is None:
            return
        video, _ = comfy_urls()
        try:
            # 必须在放锁之前清空视频权重，否则下一个持锁者会看到显存不足。
            best_effort(video, '/free', {'unload_models': True, 'free_memory': True})
            wait_free(self.minimum_free_mib, timeout=60)
        except Exception:
            # 释放失败不掩盖原始异常；下一次持锁者仍会用显存门槛自保。
            pass
        finally:
            self.handle.close()
            self.handle = None
