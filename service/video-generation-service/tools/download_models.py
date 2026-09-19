#!/usr/bin/env python3
"""仅下载锁定的三个基础权重，校验后原子发布；不会加载 GPU。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path('/opt/yuyutian/mytools/runtime/video-generation')


def digest(path):
    """以固定内存读取大模型的 SHA-256。"""
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    """只接受已固定的公开仓库和版本，保留失败下载供重试。"""
    lock = json.loads(Path(sys.argv[1]).read_text())
    assert lock['repository'] == 'Comfy-Org/Wan_2.1_ComfyUI_repackaged'
    assert lock['revision'] == '617a7633e636506f850e043bc4605f290a466a8e'
    evidence = []
    for item in lock['files']:
        if 't2v_1.3B' in item['path']:
            continue
        relative = Path(item['path'])
        assert relative.parts[0] == 'split_files' and '..' not in relative.parts
        target = ROOT / 'runtime-v1/ComfyUI/models' / Path(*relative.parts[1:])
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            assert target.stat().st_size == item['bytes'] and digest(target) == item['sha256']
        else:
            partial = target.with_suffix('.partial')
            url = f"https://huggingface.co/{lock['repository']}/resolve/{lock['revision']}/{item['path']}"
            print(json.dumps({'event': 'downloading', 'file': target.name}), flush=True)
            subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                            '--proxy', 'http://127.0.0.1:17890', '--continue-at', '-',
                            '--retry', '5', '--connect-timeout', '30', '--max-time', '14400',
                            '--output', str(partial), url], check=True)
            assert partial.stat().st_size == item['bytes'], 'MODEL_SIZE_MISMATCH'
            assert digest(partial) == item['sha256'], 'MODEL_HASH_MISMATCH'
            partial.rename(target)
            target.chmod(0o440)
        evidence.append({**item, 'verifiedAtEpoch': time.time()})
        (ROOT / 'evidence/models.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps({'event': 'verified', 'file': target.name}), flush=True)


if __name__ == '__main__':
    main()
