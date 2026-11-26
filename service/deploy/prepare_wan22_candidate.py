"""从官方镜像准备 Wan 2.2 候选，完整校验后才供推理使用。"""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path('/opt/yuyutian/mytools/runtime/video-generation')
FILES = [
    ('diffusion_models', 'wan2.2_ti2v_5B_fp16.safetensors', 9999658848,
     '456f901338bd9eadbded3828b819109a9b68e8a525ca5cf8d0049a69fcfeca1e'),
    ('vae', 'wan2.2_vae.safetensors', 1409400960,
     'e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156'),
]


def main():
    """保留断点文件，拒绝覆盖未匹配摘要的现有权重。"""
    records = []
    for folder, name, size, digest in FILES:
        target = ROOT / 'runtime-v1/ComfyUI/models' / folder / name
        target.parent.mkdir(exist_ok=True)
        partial = target.with_suffix('.safetensors.partial')
        url = 'https://modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/' + folder + '/' + name
        if not target.exists():
            # 镜像版本可能变化，最终长度与官方锁定摘要共同约束文件身份。
            subprocess.run(['curl', '-fsSL', '--noproxy', '*', '--connect-timeout', '15',
                            '--max-time', '900', '--retry', '1', '--retry-max-time', '930',
                            '--continue-at', '-', '--max-filesize', str(size), '-o', str(partial), url], check=True)
            if partial.stat().st_size != size:
                raise RuntimeError('model_size_mismatch')
            with partial.open('rb') as source:
                if hashlib.file_digest(source, 'sha256').hexdigest() != digest:
                    raise RuntimeError('model_hash_mismatch')
            partial.rename(target)
            target.chmod(0o644)
        with target.open('rb') as source:
            if target.stat().st_size != size or hashlib.file_digest(source, 'sha256').hexdigest() != digest:
                raise RuntimeError('existing_model_mismatch')
        records.append({'file':name, 'bytes':size, 'sha256':digest, 'verified':True})
        print(json.dumps(records[-1]), flush=True)
        (ROOT / 'wan22-candidate-20260928-models.json').write_text(json.dumps(records, indent=2))


if __name__ == '__main__':
    main()
