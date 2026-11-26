"""Install the reviewed Gateway LAN delta through the configured SSH host."""
from pathlib import Path
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request

STAGE = Path(__file__).resolve().parent
RELEASE = Path('/opt/yuyutian/mytools/releases/global-lan-20261102-v3')
UNIT = 'mytools-mytools-gateway.service'
DROPIN = Path('/etc/systemd/system') / (UNIT + '.d') / 'zzzzzzzz-global-lan-20261102.conf'
NGINX = Path('/etc/nginx/sites-available/mytools-gateway-lan')
ENABLED = Path('/etc/nginx/sites-enabled/mytools-gateway-lan')
AVAHI = Path('/etc/avahi/services/mytools-gateway.service')
DELTA_SHA = 'ac5d2873cf677af489aa532476b5f868e259915d9f21c11f92e8e4b78a97620b'


LAST_STEP = ''

def run(*args):
    global LAST_STEP
    LAST_STEP = Path(args[1]).name if args[0] == 'python3' else args[0] + ':' + (args[1] if len(args) > 1 else '')
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def health():
    for _ in range(45):
        try:
            with urllib.request.urlopen('http://127.0.0.1:23200/actuator/health', timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Gateway health check timed out')


def main():
    if os.geteuid() != 0:
        raise RuntimeError('This installer requires sudo')
    for target in (RELEASE, DROPIN, NGINX, ENABLED, AVAHI):
        if target.exists() or target.is_symlink():
            raise RuntimeError('Refusing to replace an existing installation path')
    service = run('systemctl', 'show', UNIT, '-p', 'ExecStart', '--value')
    matched = re.search(r'argv\[\]=(.*?) ; ignore_errors=', service)
    if matched is None:
        raise RuntimeError('Unable to preserve the existing Gateway command')
    command = shlex.split(matched.group(1))
    jar_index = command.index('-jar') + 1
    original = Path(command[jar_index])
    if not original.is_file():
        raise RuntimeError('Original Gateway JAR is missing')
    RELEASE.mkdir(mode=0o755)
    patch_result = run('python3', str(STAGE / 'apply_gateway_lan_delta.py'),
        '--base-jar', str(original), '--delta', str(STAGE / 'delta.zip'),
        '--output', str(RELEASE / 'mytools-gateway.jar'), '--expected-delta-sha256', DELTA_SHA)
    receipt = json.loads(patch_result)
    run('python3', str(STAGE / 'generate_gateway_lan_config.py'),
        '--lan-address', '192.168.1.7', '--lan-address', '192.168.3.159',
        '--dsh-proxy-config', '/etc/nginx/sites-enabled/dsh-lan', '--output', str(STAGE / 'rendered-v3'))
    command[jar_index] = str(RELEASE / 'mytools-gateway.jar')
    def quote(value):
        return json.dumps(value.replace('%', '%%'), ensure_ascii=False)
    DROPIN.parent.mkdir(parents=True, exist_ok=True)
    try:
        with DROPIN.open('x') as output:
            output.write(chr(10).join(['[Service]', 'ExecStart=', 'ExecStart=' + ' '.join(quote(value) for value in command), '']))
        shutil.copyfile(STAGE / 'rendered-v3/mytools-gateway-lan.conf', NGINX)
        NGINX.chmod(0o600)
        ENABLED.symlink_to(NGINX)
        run('nginx', '-t')
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', UNIT)
        health()
        live = run('systemctl', 'show', UNIT, '-p', 'ExecStart', '--value')
        if str(RELEASE / 'mytools-gateway.jar') not in live:
            raise RuntimeError('Gateway command was overridden by another drop-in')
        run('systemctl', 'reload', 'nginx')
        shutil.copyfile(STAGE / 'rendered-v3/mytools-gateway.service', AVAHI)
        AVAHI.chmod(0o644)
        run('systemctl', 'reload-or-restart', 'avahi-daemon')
    except Exception:
        for target in (AVAHI, ENABLED, NGINX, DROPIN):
            target.unlink(missing_ok=True)
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', UNIT)
        health()
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
        run('systemctl', 'reload-or-restart', 'avahi-daemon')
        raise
    receipt.update({'deployed': True, 'gatewayHealthy': True, 'lanPort': 23201,
        'legacyPortUnchanged': 23110, 'privateInterfaceCount': 2,
        'rollback': 'Remove only zzzzzzzz-global-lan-20261102.conf and the new nginx/avahi files, daemon-reload, restart Gateway and reload nginx/avahi.'})
    (STAGE / 'deployment.json').write_text(json.dumps(receipt, indent=2) + chr(10))
    print(json.dumps(receipt))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'deployed': False, 'errorType': type(error).__name__, 'lastStep': LAST_STEP}), file=sys.stderr)
        sys.exit(1)
