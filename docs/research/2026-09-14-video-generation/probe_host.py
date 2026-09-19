"""只读采集视频方案需要的资源信息，不读取凭据、环境文件或业务内容。"""
import datetime
import json
from pathlib import Path
import subprocess
import urllib.request


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30)
    return result.stdout.strip() if result.returncode == 0 else 'unavailable'


root = Path('/opt/yuyutian/mytools/runtime/krea2-evaluation-20260913')
mem = {}
for line in Path('/proc/meminfo').read_text().splitlines():
    key, value = line.split(':', 1)
    if key in {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}:
        mem[key] = value.strip()
os_info = {}
for line in Path('/etc/os-release').read_text().splitlines():
    if line.startswith(('PRETTY_NAME=', 'VERSION_ID=')):
        key, value = line.split('=', 1)
        os_info[key] = value.strip('"')
report = {
    'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'os': os_info,
    'kernel': command(['uname', '-sr']),
    'gpu': command(['nvidia-smi', '--query-gpu=name,memory.total,memory.used,driver_version', '--format=csv,noheader']),
    'memory': mem,
    'disk': command(['df', '-B1', '/opt/yuyutian/mytools']),
    'cpu': command(['lscpu', '-J']),
    'memoryPressure': Path('/proc/pressure/memory').read_text().strip(),
    'vmstat': command(['vmstat', '1', '3']),
    'ffmpeg': command(['ffmpeg', '-version']).splitlines()[0],
    'comfyRevision': command(['git', '-c', 'safe.directory='+str(root/'ComfyUI'), '-C', str(root/'ComfyUI'), 'rev-parse', 'HEAD']),
    'torch': command([str(root/'venv/bin/python'), '-c', 'import json,torch;print(json.dumps({"torch":torch.__version__,"cuda":torch.version.cuda,"archList":torch.cuda.get_arch_list()}))']),
    'services': {},
}
for name in ['image-generation-service', 'mytools-gateway', 'task-scheduler-service', 'task-executor-service', 'image-comfy', 'reader-service']:
    report['services'][name] = command(['systemctl', 'show', 'mytools-'+name, '-p', 'ActiveState', '-p', 'MemoryCurrent', '-p', 'MemoryMax', '-p', 'MemorySwapMax'])
for key, url in [('comfy', 'http://127.0.0.1:8189/queue'), ('ollama', 'http://127.0.0.1:11434/api/tags')]:
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            data = json.load(response)
        report[key] = ({'running': len(data.get('queue_running', [])), 'pending': len(data.get('queue_pending', []))} if key == 'comfy' else [{'name': x['name'], 'size': x.get('size')} for x in data.get('models', [])])
    except Exception:
        report[key] = 'unavailable'
report['modelFiles'] = [{'name': str(p.relative_to(root/'ComfyUI/models')), 'bytes': p.stat().st_size} for p in (root/'ComfyUI/models').rglob('*') if p.is_file() and p.suffix in {'.safetensors', '.gguf', '.pth', '.pt'}]
print(json.dumps(report, indent=2))
