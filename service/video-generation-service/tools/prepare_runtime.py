#!/usr/bin/env python3
"""从已验证图片环境复制隔离的视频运行时，不修改生产文件。"""
import json
from pathlib import Path
import subprocess

SOURCE = Path('/opt/yuyutian/mytools/runtime/krea2-evaluation-20260913')
ROOT = Path('/opt/yuyutian/mytools/runtime/video-generation')
TARGET = ROOT / 'runtime-v1'
REVISION = '02d39c8cd7828566f48ccf783c1c75b8336044f5'


def run(*args):
    """执行失败立即退出，避免发布不完整环境。"""
    return subprocess.check_output(args, text=True).strip()


def main():
    """复制独立文件并重定位 Python 路径，完成后写入版本证据。"""
    assert run('git', '-C', str(SOURCE / 'ComfyUI'), 'rev-parse', 'HEAD') == REVISION
    assert not TARGET.exists(), 'RUNTIME_ALREADY_EXISTS'
    TARGET.mkdir(parents=True)
    for name in ('python', 'venv'):
        subprocess.run(['cp', '-a', '--reflink=auto', str(SOURCE / name), str(TARGET / name)], check=True)
    for path in (TARGET / 'venv/bin').iterdir():
        # 仅重写副本中的文本启动器；共享库和生产解释器保持不变。
        if path.is_symlink():
            target = str(path.readlink())
            if str(SOURCE) in target:
                path.unlink()
                path.symlink_to(target.replace(str(SOURCE), str(TARGET)))
        elif path.is_file():
            data = path.read_bytes()
            if b'\x00' not in data:
                path.write_bytes(data.replace(str(SOURCE).encode(), str(TARGET).encode()))
    config = TARGET / 'venv/pyvenv.cfg'
    config.write_text(config.read_text().replace(str(SOURCE), str(TARGET)))
    comfy = TARGET / 'ComfyUI'
    comfy.mkdir()
    archive = subprocess.Popen(['git', '-C', str(SOURCE / 'ComfyUI'), 'archive', REVISION], stdout=subprocess.PIPE)
    subprocess.run(['tar', '-x', '-C', str(comfy)], stdin=archive.stdout, check=True)
    assert archive.wait() == 0
    for name in ('models', 'workflows', 'input', 'output', 'evidence'):
        (ROOT / name).mkdir(exist_ok=True)
    evidence = {'comfyRevision': REVISION, 'sourceRuntime': str(SOURCE), 'runtime': str(TARGET),
                'environmentMethod': 'independent copy with relocated interpreter; no production package mutation',
                'python': run(str(TARGET / 'venv/bin/python'), '-V')}
    (ROOT / 'evidence/runtime.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence), flush=True)


if __name__ == '__main__':
    main()
