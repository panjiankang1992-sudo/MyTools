#!/usr/bin/env python3
"""本地文件锁长等待复现，不连接生产服务或使用 GPU。"""
import argparse
import fcntl
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    """父进程持锁、独立子进程调用实际包的 acquire，避免继承锁描述符。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--module', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--hold-seconds', type=float, default=130)
    parser.add_argument('--child-lock', type=Path)
    args = parser.parse_args()
    if args.child_lock:
        spec = importlib.util.spec_from_file_location('tested_gpu_lease', args.module)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        started = time.monotonic()
        with args.child_lock.open('a') as handle:
            try:
                module.acquire(handle)
                outcome = 'ACQUIRED'
            except TimeoutError:
                outcome = 'GPU_BUSY'
        result = {'scope': 'local real file-lock contention; no GPU inference',
                  'module': str(args.module), 'holdSeconds': args.hold_seconds,
                  'waitSeconds': round(time.monotonic() - started, 2), 'outcome': outcome,
                  'B06LongWaitPassed': outcome == 'ACQUIRED'}
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
        return
    if not 0 < args.hold_seconds <= 1800:
        parser.error('hold-seconds must be in (0,1800]')
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'gpu.lock'
        with path.open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            child = subprocess.Popen([sys.executable, __file__, '--module', str(args.module),
                                      '--output', str(args.output), '--hold-seconds', str(args.hold_seconds),
                                      '--child-lock', str(path)], close_fds=True)
            try:
                time.sleep(args.hold_seconds)
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)
        try:
            child.wait(timeout=30)
        except subprocess.TimeoutExpired:
            child.terminate()
            child.wait(timeout=10)
            raise


if __name__ == '__main__':
    main()
