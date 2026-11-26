#!/usr/bin/env python3
"""为模型服务后续启动添加 GPU 就绪检查，不中断当前推理。"""
from pathlib import Path
import shutil
import subprocess


def main():
    """验证设备并幂等安装启动检查。"""
    binary = shutil.which('nvidia-smi')
    assert binary, 'gpu_probe_missing'
    subprocess.run([binary, '-L'], check=True, stdout=subprocess.DEVNULL)
    target = Path('/etc/systemd/system/ollama.service.d/60-mytools-gpu-ready.conf')
    content = '[Unit]\nAfter=nvidia-persistenced.service\n\n[Service]\nExecStartPre=' + binary + ' -L\n'
    assert not target.exists() or target.read_text() == content, 'existing_override_conflict'
    target.parent.mkdir(exist_ok=True)
    target.write_text(content)
    subprocess.run(['systemctl', 'daemon-reload'], check=True)
    print('GPU readiness preflight configured')


if __name__ == '__main__':
    main()
