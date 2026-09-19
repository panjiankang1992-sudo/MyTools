#!/usr/bin/env python3
"""Stage and activate one bounded adaptation production release; never print secrets."""
import base64
import hashlib
import json
import os
from pathlib import Path
import pwd
import secrets
import shlex
import shutil
import socket
import ssl
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request

ROOT = Path('/opt/yuyutian/mytools')
BASE = ROOT / 'runtime/adaptation-production-20260911'
OLD = ROOT / 'releases/chapter-adaptation-20260911-v2'
NEW = ROOT / 'releases/chapter-adaptation-production-20260911-v3'
ACCEPT = ROOT / 'runtime/adaptation-acceptance-20260911'
UPLOAD = Path('/tmp/mytools-adaptation-production-20260911')
DEPLOYMENT = 'sillytraven-production-20260911-g2'
MODEL = 'x-apex-sigma-0829-64k'
NETWORK = 'mytools-adaptation-runtime-prod'
CHAIN = 'MYTOOLS_ADAPT_OUT'
NODE = 'adaptation-production-executor'
SERVICES = ('reader-service', 'task-scheduler-service', 'task-executor-service', 'mytools-gateway')


def run(args, **kwargs):
    """Capture subprocess diagnostics privately; no command or secret echo."""
    result = subprocess.run(list(map(str, args)), capture_output=True, timeout=kwargs.pop('timeout', 60), **kwargs)
    if result.returncode:
        if BASE.exists():
            write(BASE / 'operator/last-command-error.log', result.stderr)
        raise RuntimeError('command_failed:' + Path(str(args[0])).name + ':' + str(args[1]))
    return result.stdout


def values(path):
    """Read private environment or simple properties without exporting contents."""
    result = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.strip().partition('=')
        if separator and not key.startswith('#'):
            result[key] = value
    return result


def environment():
    return {key: shlex.split(value)[0] if value else '' for key, value in values(ROOT / 'config/services.env').items()}


def write(path, content, user='root', mode=0o600):
    """Write only exact deployment-owned files with explicitly narrow permissions."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode())
    os.chown(path, pwd.getpwnam(user).pw_uid, pwd.getpwnam(user).pw_gid)
    path.chmod(mode)


def props(path, data, user):
    assert all('\n' not in str(value) and '\r' not in str(value) for value in data.values())
    write(path, ''.join(key + '=' + str(value) + '\n' for key, value in data.items()), user)


def audit(name, data):
    write(BASE / 'operator' / (name + '.json'), json.dumps(data, ensure_ascii=True, sort_keys=True))
    print(json.dumps(data, ensure_ascii=True), flush=True)


def identity(role):
    return 'mytools-adapt-' + role


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked_current(expected):
    assert (ROOT / 'releases/current').resolve(strict=True) == expected, 'release_changed'


def connection(prefix):
    import pymysql
    env = environment()
    assert prefix in ('READER', 'TASK')
    return pymysql.connect(host=env.get(prefix + '_DB_HOST', '127.0.0.1'), port=int(env.get(prefix + '_DB_PORT', '3306')),
        user=env.get(prefix + '_DB_USER') or env.get(prefix + '_DB_USERNAME'), password=env[prefix + '_DB_PASSWORD'],
        database='mytools_reader' if prefix == 'READER' else 'mytools_task', autocommit=False)


def request(port, path, method='GET', data=None, tls=False, client=True, role='reader'):
    """Use bounded authenticated loopback APIs; callers redact response bodies."""
    env = environment()
    headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + env['READER_INTERNAL_TOKEN'],
        'X-Task-Service-Id': 'task-operator-service', 'X-Task-Internal-Token': env['TASK_OPERATOR_INTERNAL_TOKEN']}
    context = None
    if tls:
        context = ssl.create_default_context(cafile=str(BASE / 'operator/ca.crt'))
        if client:
            context.load_cert_chain(str(BASE / role / 'tls.crt'), str(BASE / role / 'tls.key'))
    target = ('https://localhost:' if tls else 'http://127.0.0.1:') + str(port) + path
    call = urllib.request.Request(target, headers=headers, method=method,
        data=None if data is None else json.dumps(data, ensure_ascii=False).encode())
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
        response = opener.open(call, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read(2097153)
        assert len(raw) <= 2097152
        return response.status, json.loads(raw) if raw else None


def certificates():
    """Create a production-only CA and role identities; never reuse test consent or TLS keys."""
    operator = BASE / 'operator'
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-sha256', '-days', '1825',
        '-subj', '/CN=MyTools adaptation production CA', '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
        '-keyout', operator / 'ca.key', '-out', operator / 'ca.crt'])
    (operator / 'ca.key').chmod(0o600)
    for role in ('reader', 'scheduler', 'executor'):
        directory = BASE / role
        user = identity(role)
        password = secrets.token_urlsafe(32)
        write(directory / 'tls.password', password, user)
        extension = ('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
            'extendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1,URI:spiffe://mytools.local/production/' + role + '\n')
        write(operator / (role + '.ext'), extension)
        run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=adaptation-production-' + role,
            '-keyout', directory / 'tls.key', '-out', operator / (role + '.csr')])
        run(['openssl', 'x509', '-req', '-in', operator / (role + '.csr'), '-CA', operator / 'ca.crt',
            '-CAkey', operator / 'ca.key', '-CAcreateserial', '-days', '365', '-sha256',
            '-extfile', operator / (role + '.ext'), '-out', directory / 'tls.crt'])
        run(['openssl', 'verify', '-x509_strict', '-CAfile', operator / 'ca.crt', directory / 'tls.crt'])
        run(['openssl', 'pkcs12', '-export', '-in', directory / 'tls.crt', '-inkey', directory / 'tls.key',
            '-certfile', operator / 'ca.crt', '-name', role, '-out', directory / 'tls.p12', '-passout', 'file:' + str(directory / 'tls.password')])
        run(['keytool', '-importcert', '-noprompt', '-alias', 'production-ca', '-file', operator / 'ca.crt',
            '-keystore', directory / 'trust.p12', '-storetype', 'PKCS12', '-storepass:file', directory / 'tls.password'])
        for file in ('tls.key', 'tls.crt', 'tls.p12', 'trust.p12'):
            write(directory / file, (directory / file).read_bytes(), user)
    directory = BASE / 'executor'
    run(['keytool', '-importkeystore', '-noprompt', '-srckeystore', '/etc/ssl/certs/java/cacerts', '-srcstorepass', 'changeit',
        '-destkeystore', directory / 'public-trust.jks', '-deststoretype', 'JKS', '-deststorepass:file', directory / 'tls.password'])
    run(['keytool', '-importcert', '-noprompt', '-alias', 'production-ca', '-file', operator / 'ca.crt',
        '-keystore', directory / 'public-trust.jks', '-storepass:file', directory / 'tls.password'])
    write(directory / 'public-trust.jks', (directory / 'public-trust.jks').read_bytes(), identity('executor'))
    run(['openssl', 'genpkey', '-algorithm', 'Ed25519', '-out', operator / 'signer.pem'])
    private = run(['openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', operator / 'signer.pem', '-outform', 'DER'])
    public = run(['openssl', 'pkey', '-in', operator / 'signer.pem', '-pubout', '-outform', 'DER'])
    write(BASE / 'scheduler/signing.json', json.dumps({'activeKeyId': 'prod-v1', 'keys': [{'kid': 'prod-v1',
        'privateKey': base64.b64encode(private).decode(), 'publicKey': base64.b64encode(public).decode()}]}), identity('scheduler'))
    for file in ('locator', 'settlement'):
        write(BASE / ('reader/' + file + '.json'), json.dumps({'activeKeyId': 'prod-v1',
            'keys': {'prod-v1': base64.b64encode(secrets.token_bytes(32)).decode()}}), identity('reader'))
    write(BASE / 'executor/relay.key', secrets.token_bytes(32), identity('executor'))


def listener(role, port):
    directory = BASE / role
    return {'workload.listener.enabled': 'true', 'workload.listener.port': port,
        'workload.listener.key-store-file': directory / 'tls.p12', 'workload.listener.trust-store-file': directory / 'trust.p12',
        'workload.listener.key-store-password-file': directory / 'tls.password', 'workload.listener.trust-store-password-file': directory / 'tls.password'}


def configure():
    env = environment()
    reader = listener('reader', 24231)
    reader.update({'reader.adaptation.read-enabled': 'true', 'reader.adaptation.create-enabled': 'false',
        'reader.adaptation.provider-deployment-id': DEPLOYMENT, 'reader.adaptation.disclosure-version': 'production-20260911-v1',
        'reader.adaptation-dispatch.enabled': 'true', 'reader.adaptation-recovery.enabled': 'true',
        'reader.shelf-chapters.enabled': 'true', 'reader.shelf-chapters.runtime-egress-verified': 'true',
        'reader.shelf-chapters.locator-keyring-file': BASE / 'reader/locator.json',
        'reader.attempt-settlement.keyring-file': BASE / 'reader/settlement.json',
        'reader.runtime-base-url': 'http://127.0.0.1:24121', 'reader.runtime-secure-key': values(BASE / 'runtime/runtime.env')['SECURE_KEY'],
        'reader.workload-authorization.enabled': 'true', 'reader.workload-authorization.scheduler-url': 'https://localhost:24411',
        'reader.workload-authorization.identity': 'spiffe://mytools.local/production/reader',
        'reader.workload-authorization.executor-identities[0]': 'spiffe://mytools.local/production/executor'})
    for key, file in (('key-store-file', 'tls.p12'), ('trust-store-file', 'trust.p12'), ('key-store-password-file', 'tls.password'), ('trust-store-password-file', 'tls.password')):
        reader['reader.workload-authorization.' + key] = BASE / 'reader' / file
    props(BASE / 'reader/application.properties', reader, identity('reader'))
    scheduler = listener('scheduler', 24411)
    scheduler.update({'task.workload-authorization.enabled': 'true',
        'task.workload-authorization.signing-keyring-file': BASE / 'scheduler/signing.json',
        'task.workload-authorization.executor-identities[' + NODE + ']': 'spiffe://mytools.local/production/executor',
        'task.workload-authorization.reader-identities[0]': 'spiffe://mytools.local/production/reader',
        'task.node-registration.allowed-cluster-names': env.get('TASK_ALLOWED_EXECUTOR_CLUSTERS',
            'media,reader,reader-probe-orchestration,download,download-orchestration,messaging,asset,drive,identity,storage') + ',reader-adaptation',
        'task.node-registration.trusted-labels[reader.adaptation]': 'enabled',
        'task.reader-adaptation-deployment.configured': 'true', 'task.reader-adaptation-deployment.active': 'true',
        'task.reader-adaptation-deployment.audit-id': NEW.name})
    props(BASE / 'scheduler/application.properties', scheduler, identity('scheduler'))
    executor = {key: value.replace(str(ACCEPT), str(BASE)).replace('/run/mytools-adapt-accept-executor', '/run/mytools-adaptation-executor')
        for key, value in values(ACCEPT / 'executor/application.properties').items()}
    executor.update({'server.port': '24102', 'management.server.port': '24102', 'executor.node-name': NODE,
        'executor.scheduler-url': 'https://localhost:24411', 'executor.internal-token': env['TASK_EXECUTOR_INTERNAL_TOKEN'],
        'executor.workload-tls.identity': 'spiffe://mytools.local/production/executor',
        'executor.script-root': NEW / 'task-packages', 'executor.python-sdk-root': NEW / 'task-executor-sdk',
        'executor.novel-adaptation.reader-url': 'https://localhost:24231', 'executor.novel-adaptation.provider-deployment-id': DEPLOYMENT,
        'executor.novel-adaptation.credential-generation': '2'})
    props(BASE / 'executor/application.properties', executor, identity('executor'))
    props(BASE / 'gateway/application.properties', {'gateway.chapter-adaptation.read-enabled': 'true',
        'gateway.chapter-adaptation.create-enabled': 'false'}, 'mytools')


def register_provider():
    """Publish an honest production notice, without granting consent to any account."""
    compact = lambda value: json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    contract = {'schemaVersion': 'production-provider-contract-v1', 'endpoint': 'https://api.sillytraven.dev/api/ai/v1/chat/completions',
        'model': MODEL, 'purpose': 'user-requested shelf chapter adaptation', 'retention': 'unverified', 'credentialGeneration': 2}
    contract_sha = hashlib.sha256(compact(contract).encode()).hexdigest()
    notice = {'schemaVersion': 'adaptation-disclosure-v1', 'providerCode': 'sillytraven', 'contractSha256': contract_sha,
        'providerName': 'SillyTraven API', 'providerOrigin': 'https://api.sillytraven.dev',
        'dataUseNotice': '\u6240\u9009\u7ae0\u8282\u539f\u6587\u3001\u76f8\u90bb\u7ae0\u8282\u4e0a\u4e0b\u6587\u3001\u6539\u7f16\u610f\u56fe\u3001\u4f18\u5316\u6240\u9700\u7684\u65e2\u6709\u7ed3\u679c\u53ca\u8bc4\u5ba1\u5019\u9009\uff0c\u5c06\u53d1\u9001\u7ed9\u4f60\u6307\u5b9a\u7684 SillyTraven API \u8fdb\u884c\u751f\u6210\u548c\u6821\u9a8c\u3002\u539f\u4e66\u4e0d\u4f1a\u88ab\u8986\u76d6\uff0c\u64cd\u4f5c\u5386\u53f2\u548c\u7ed3\u679c\u4fdd\u5b58\u5728 MyTools\u3002',
        'retentionNotice': '\u7b2c\u4e09\u65b9\u7684\u5904\u7406\u5730\u533a\u3001\u7559\u5b58\u671f\u9650\u3001\u8bad\u7ec3\u7528\u9014\u548c\u5220\u9664\u673a\u5236\u5c1a\u672a\u6838\u5b9e\uff0c\u65e0\u6cd5\u627f\u8bfa\u4e0d\u7559\u5b58\u6216\u4e0d\u7528\u4e8e\u8bad\u7ec3\u3002\u8bf7\u52ff\u63d0\u4ea4\u654f\u611f\u5185\u5bb9\u3002\u64a4\u9500\u6388\u6743\u53ef\u963b\u6b62\u540e\u7eed\u6388\u6743\u8c03\u7528\uff0c\u4f46\u4e0d\u80fd\u64a4\u56de\u5df2\u7ecf\u53d1\u9001\u7684\u6570\u636e\u3002',
        'rightsNotice': '\u8bf7\u786e\u8ba4\u4f60\u6709\u6743\u5c06\u6240\u9009\u5c0f\u8bf4\u53ca\u4e0a\u4e0b\u6587\u63d0\u4ea4\u7ed9\u8be5\u7b2c\u4e09\u65b9\u5904\u7406\uff0c\u5e76\u786e\u8ba4\u8fd9\u662f\u4f60\u4e3b\u52a8\u53d1\u8d77\u7684\u6539\u7f16\u8981\u6c42\u3002\u6ca1\u6709\u76f8\u5e94\u6388\u6743\u7684\u5185\u5bb9\uff0c\u8bf7\u52ff\u63d0\u4ea4\u3002'}
    notice_sha = hashlib.sha256(compact(notice).encode()).hexdigest()
    with connection('READER') as db, db.cursor() as cursor:
        cursor.execute('SELECT COUNT(*) FROM novel_adaptation_provider_deployment WHERE id=%s', (DEPLOYMENT.encode(),))
        assert cursor.fetchone()[0] == 0, 'provider_already_registered'
        cursor.execute('INSERT INTO novel_adaptation_provider_deployment VALUES (%s,%s,%s,%s,%s,TRUE,CURRENT_TIMESTAMP)',
            (DEPLOYMENT.encode(), 'sillytraven', MODEL.encode(), 2, contract_sha))
        cursor.execute('INSERT INTO reader_adaptation_provider_disclosure VALUES (%s,%s,%s,%s,TRUE,CURRENT_TIMESTAMP)',
            (b'production-20260911-v1', notice_sha, b'production-rights-v1', compact(notice)))
        for kind, scope in (('AUTH', '2'), ('AVAILABILITY', MODEL)):
            bucket = hashlib.sha256((kind + ':' + DEPLOYMENT + ':' + scope).encode()).hexdigest()
            cursor.execute('INSERT INTO novel_adaptation_provider_circuit (bucket_id,bucket_kind,provider_deployment_id,scope_key,state) VALUES (%s,%s,%s,%s,%s)',
                (bucket, kind, DEPLOYMENT.encode(), scope.encode(), 'CLOSED'))
        db.commit()
    write(BASE / 'operator/provider-contract.json', compact(contract))
    write(BASE / 'operator/disclosure.json', compact(notice))
    audit('provider-registration', {'auditId': NEW.name, 'provider': DEPLOYMENT, 'generation': 2,
        'contractSha256': contract_sha, 'disclosureSha256': notice_sha, 'consentsCreated': 0})


def runtime_prepare():
    """Create a separate pinned runtime; original reading container stays untouched."""
    info = json.loads(run(['docker', 'inspect', 'mytools-reader-runtime']))[0]
    assert '@sha256:' in info['Config']['Image']
    runtime_env = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
    runtime_env.update({'SECURE': 'true', 'SECURE_KEY': secrets.token_urlsafe(40), 'SERVER_HOST': '0.0.0.0', 'SERVER_PORT': '8080',
        'DATABASE_URL': 'sqlite:/app/storage/reader.db?mode=rwc', 'STORAGE_DIR': '/app/storage', 'ASSETS_DIR': '/app/storage/assets'})
    props(BASE / 'runtime/runtime.env', runtime_env, 'root')
    (BASE / 'runtime/storage').mkdir(mode=0o700)
    write(BASE / 'runtime/image', info['Config']['Image'])


def runtime_start():
    """Bind firewall rules to the dedicated bridge, never to unrelated host traffic."""
    inspect = subprocess.run(['docker', 'network', 'inspect', NETWORK], capture_output=True)
    if inspect.returncode:
        run(['docker', 'network', 'create', '--label', 'mytools.audit=' + NEW.name, NETWORK])
    network = json.loads(run(['docker', 'network', 'inspect', NETWORK]))[0]
    assert not network['EnableIPv6'] and network['Labels'].get('mytools.audit') == NEW.name
    bridge = 'br-' + network['Id'][:12]
    if subprocess.run(['iptables', '-S', CHAIN], capture_output=True).returncode:
        run(['iptables', '-N', CHAIN])
    rules = [['-m', 'conntrack', '--ctstate', 'ESTABLISHED,RELATED', '-j', 'RETURN']]
    for network_range in ('0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8', '169.254.0.0/16', '172.16.0.0/12',
            '192.0.0.0/24', '192.0.2.0/24', '192.168.0.0/16', '198.18.0.0/15', '198.51.100.0/24', '203.0.113.0/24', '224.0.0.0/4', '240.0.0.0/4'):
        rules.append(['-d', network_range, '-j', 'REJECT'])
    rules += [['-p', 'tcp', '-m', 'multiport', '--dports', '80,443', '-j', 'RETURN'],
        ['-p', 'udp', '--dport', '53', '-j', 'RETURN'], ['-p', 'tcp', '--dport', '53', '-j', 'RETURN'], ['-j', 'REJECT']]
    for rule in rules:
        if subprocess.run(['iptables', '-C', CHAIN, *rule], capture_output=True).returncode:
            run(['iptables', '-A', CHAIN, *rule])
    host_chain = 'MYTOOLS_ADAPT_HOST'
    if subprocess.run(['iptables', '-S', host_chain], capture_output=True).returncode:
        run(['iptables', '-N', host_chain])
    for rule in (['-m', 'conntrack', '--ctstate', 'ESTABLISHED,RELATED', '-j', 'RETURN'], ['-j', 'REJECT']):
        if subprocess.run(['iptables', '-C', host_chain, *rule], capture_output=True).returncode:
            run(['iptables', '-A', host_chain, *rule])
    for parent, target in (('FORWARD', CHAIN), ('DOCKER-USER', CHAIN), ('INPUT', host_chain)):
        rule = ['-i', bridge, '-j', target]
        if subprocess.run(['iptables', '-C', parent, *rule], capture_output=True).returncode:
            run(['iptables', '-I', parent, '1', *rule])
    existing = subprocess.run(['docker', 'container', 'inspect', NETWORK], capture_output=True)
    if existing.returncode:
        run(['docker', 'create', '--name', NETWORK, '--label', 'mytools.audit=' + NEW.name, '--network', NETWORK,
            '--read-only', '--tmpfs', '/tmp:rw,nosuid,nodev,size=64m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
            '--sysctl', 'net.ipv6.conf.all.disable_ipv6=1', '--publish', '127.0.0.1:24121:8080', '--env-file', BASE / 'runtime/runtime.env',
            '--mount', 'type=bind,source=' + str(BASE / 'runtime/storage') + ',destination=/app/storage',
            '--log-opt', 'max-size=1m', '--log-opt', 'max-file=1', (BASE / 'runtime/image').read_text()])
    run(['docker', 'start', NETWORK])


def unit(role, service):
    user = identity(role) if role != 'gateway' else 'mytools'
    dependency = '[Unit]\nRequires=mytools-adaptation-runtime.service\nAfter=mytools-adaptation-runtime.service\n' if role == 'reader' else ''
    environment = 'UnsetEnvironment=READER_RUNTIME_BASE_URL READER_RUNTIME_SECURE_KEY\n' if role == 'reader' else ''
    return (dependency + '[Service]\n' + environment + 'User=' + user + '\nGroup=mytools\nExecStart=\nExecStart=/usr/bin/java -jar '
        + str(NEW / 'apps' / (service + '.jar')) + ' --spring.config.additional-location=' + str(BASE / role / 'application.properties') + '\n')


def stage():
    checked_current(OLD)
    assert not BASE.exists() and not NEW.exists(), 'stage_already_exists'
    expected = json.loads((UPLOAD / 'artifacts.json').read_text())
    for name, sha in expected.items():
        assert digest(UPLOAD / (name + '.jar')) == sha, 'uploaded_artifact_mismatch'
    assert digest(ACCEPT / 'artifacts/executor-11266021aef9f818.jar') == '11266021aef9f81838c0e05fc66a91c9c3ba738bd7b46574b9222283f5d4fc8a'
    for port in (24121, 24102, 24231, 24411):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    with connection('TASK') as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'active_tasks'
    BASE.mkdir(mode=0o750)
    os.chown(BASE, 0, pwd.getpwnam('mytools').pw_gid)
    BASE.chmod(0o750)
    for role in ('operator', 'runtime', 'reader', 'scheduler', 'executor', 'gateway'):
        directory = BASE / role
        directory.mkdir(mode=0o700)
        if role not in ('operator', 'runtime'):
            os.chown(directory, pwd.getpwnam(identity(role) if role != 'gateway' else 'mytools').pw_uid, pwd.getpwnam('mytools').pw_gid)
    write(BASE / 'operator/deploy.py', Path(__file__).read_bytes(), mode=0o700)
    write(BASE / 'operator/services.env.before', (ROOT / 'config/services.env').read_bytes())
    for service in SERVICES:
        dropin = Path('/etc/systemd/system/mytools-' + service + '.service.d/adaptation-production.conf')
        assert not dropin.exists(), 'dropin_already_exists'
    shutil.copytree(OLD, NEW, symlinks=True)
    for path in (NEW, *NEW.rglob('*')):
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid, follow_symlinks=False)
    for name in expected:
        shutil.copy2(UPLOAD / (name + '.jar'), NEW / 'apps' / (name + '.jar'))
    shutil.copy2(ACCEPT / 'artifacts/executor-11266021aef9f818.jar', NEW / 'apps/task-executor-service.jar')
    for path in (NEW / 'apps').glob('*.jar'):
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o640)
    shutil.copytree(ACCEPT / 'rootfs', BASE / 'rootfs', symlinks=True)
    for path in (BASE / 'rootfs', *(BASE / 'rootfs').rglob('*')):
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid, follow_symlinks=False)
    for relative in ('work', 'relay'):
        directory = BASE / 'executor' / relative
        directory.mkdir(mode=0o700)
        os.chown(directory, pwd.getpwnam(identity('executor')).pw_uid, pwd.getpwnam('mytools').pw_gid)
    certificates()
    write(BASE / 'executor/provider.key', (ACCEPT / 'executor/provider.key').read_bytes(), identity('executor'))
    runtime_prepare()
    configure()
    register_provider()
    audit('staged', {'release': NEW.name, 'previous': OLD.name, 'artifacts': {name: digest(NEW / 'apps' / (name + '.jar')) for name in SERVICES},
        'providerGeneration': 2, 'productionEnabled': False, 'credentialsPrinted': False, 'databaseMigrationsAdded': 0})


def wait_health(port, tls=False):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            code, body = request(port, '/actuator/health', tls=tls)
            if code == 200 and body['status'] == 'UP':
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('health_unavailable:' + str(port))


def install_units():
    for role, service in (('reader', 'reader-service'), ('scheduler', 'task-scheduler-service'), ('gateway', 'mytools-gateway')):
        write(Path('/etc/systemd/system/mytools-' + service + '.service.d/adaptation-production.conf'), unit(role, service), mode=0o644)
    runtime = ('[Unit]\nDescription=MyTools adaptation isolated source runtime\nRequires=docker.service\nPartOf=docker.service\nAfter=docker.service\n'
        '[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=/opt/yuyutian/mytools/releases/current/venv/bin/python '
        + str(BASE / 'operator/deploy.py') + ' runtime-start\nExecStop=/usr/bin/docker stop ' + NETWORK
        + '\nTimeoutStartSec=90\n[Install]\nWantedBy=mytools-services.target\n')
    write(Path('/etc/systemd/system/mytools-adaptation-runtime.service'), runtime, mode=0o644)
    executor = ('[Unit]\nDescription=MyTools production confined chapter adaptation executor\nAfter=mytools-task-scheduler-service.service mytools-reader-service.service\n'
        '[Service]\nType=simple\nUser=' + identity('executor') + '\nGroup=mytools\nWorkingDirectory=' + str(BASE / 'executor')
        + '\nExecStart=/usr/bin/java -Xmx512m -Djava.net.preferIPv6Addresses=true -Djavax.net.ssl.trustStore=' + str(BASE / 'executor/public-trust.jks')
        + ' -Djavax.net.ssl.trustStoreType=JKS -jar ' + str(NEW / 'apps/task-executor-service.jar')
        + ' --spring.config.additional-location=' + str(BASE / 'executor/application.properties')
        + '\nUMask=0077\nNoNewPrivileges=true\nProtectSystem=strict\nProtectHome=true\nPrivateTmp=true\nReadWritePaths='
        + str(BASE / 'executor') + '\nRestart=on-failure\nRestartSec=5\nTimeoutStopSec=90\nRuntimeDirectory=mytools-adaptation-executor\nRuntimeDirectoryMode=0700\n'
        '[Install]\nWantedBy=mytools-services.target\n')
    write(Path('/etc/systemd/system/mytools-adaptation-executor.service'), executor, mode=0o644)
    run(['systemctl', 'daemon-reload'])


def activate():
    checked_current(OLD)
    assert (BASE / 'operator/staged.json').exists() and not (BASE / 'operator/activated.json').exists()
    install_units()
    run(['systemctl', 'start', 'mytools-adaptation-runtime'])
    verify_runtime()
    # 先排空普通节点，防止更换 Scheduler 时丢失既有运行任务的租约。
    code, nodes = request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    for node in nodes:
        if node['name'] == 'executor-remote-1':
            code, _ = request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
                {'status': 'DRAINING', 'reason': NEW.name, 'expectedInstanceId': node['instanceId'], 'expectedRunningTasks': 0})
            assert code == 200, 'drain_rejected'
    with connection('TASK') as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'active_tasks_after_drain'
    run(['systemctl', 'stop', 'mytools-task-executor-service'])
    temporary = ROOT / 'releases/.adaptation-production-current'
    assert not temporary.exists()
    temporary.symlink_to(NEW.name)
    os.replace(temporary, ROOT / 'releases/current')
    run(['systemctl', 'restart', 'mytools-task-scheduler-service'])
    complete_activation()


def complete_activation():
    """Resume the verified post-switch sequence without rebuilding or registering again."""
    checked_current(NEW)
    assert (BASE / 'operator/staged.json').exists() and not (BASE / 'operator/activated.json').exists()
    # 控制面启动后先恢复节点，再检查依赖在线节点的聚合健康，避免互相等待。
    assert request(24411, '/api/internal/v1/task-execution-authorizations/jwks', tls=True)[0] == 200
    run(['systemctl', 'start', 'mytools-task-executor-service'])
    run(['systemctl', 'restart', 'mytools-reader-service'])
    wait_health(23230)
    wait_health(24231, True)
    run(['systemctl', 'start', 'mytools-task-executor-service', 'mytools-adaptation-executor'])
    wait_health(24102)
    wait_health(23410)
    wait_health(24411, True)
    run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    wait_health(23200)
    audit('activated', {'release': NEW.name, 'createEnabled': False, 'httpBusinessPortsPreserved': True, 'nativeMtlsPorts': [24231, 24411]})


def verify_runtime():
    info = json.loads(run(['docker', 'container', 'inspect', NETWORK]))[0]
    assert info['State']['Running'] and info['Config']['Labels'].get('mytools.audit') == NEW.name
    pid = str(info['State']['Pid'])
    # 宿主对照监听器证明服务确实可达，再验证容器被拒绝，避免把端口未监听误判为隔离成功。
    gateway = next(iter(info['NetworkSettings']['Networks'].values()))['Gateway']
    with socket.socket() as listener:
        listener.bind((gateway, 0))
        listener.listen(2)
        with socket.create_connection(listener.getsockname(), timeout=2):
            pass
        probes = [('host', gateway, listener.getsockname()[1]), ('metadata', '169.254.169.254', 80), ('public', '223.5.5.5', 443)]
        code = "import socket,json,struct; out={};\nfor name,host,port in " + repr(probes) + ":\n try:\n  c=socket.create_connection((host,port),3);c.close();out[name]=True\n except OSError: out[name]=False\n"
        code += "try:\n s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(3);s.sendto(struct.pack('!6H',16945,256,1,0,0,0)+bytes([7])+b'example'+bytes([3])+b'com'+bytes([0])+struct.pack('!2H',1,1),('127.0.0.11',53));r=s.recv(4096);s.close();out['dns']=len(r)>12 and r[:2]==b'B1' and r[3]&15==0 and struct.unpack('!H',r[6:8])[0]>0\nexcept OSError:out['dns']=False\nprint(json.dumps(out))"
        result = json.loads(run(['nsenter', '-t', pid, '-n', '/usr/bin/python3', '-c', code], timeout=15))
    audit('runtime-egress', {'runtimeSeparateFromExistingReading': True, 'networkChecks': result,
        'hostControlListenerReachable': True, 'ipv6Disabled': True})
    assert result == {'host': False, 'metadata': False, 'public': True, 'dns': True}, 'runtime_egress_failed'


def resume_original_node():
    checked_current(OLD)
    code, nodes = request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    for node in nodes:
        if node['name'] == 'executor-remote-1' and node['status'] == 'DRAINING':
            with connection('TASK') as db, db.cursor() as cursor:
                cursor.execute('SELECT status_reason FROM executor_node WHERE id=%s AND instance_id=%s', (node['id'], node['instanceId']))
                reason = cursor.fetchone()
                assert reason and reason[0] == NEW.name, 'foreign_drain'
            code, _ = request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
                {'status': 'ONLINE', 'reason': 'ADAPTATION_PREFLIGHT_CONTINUES', 'expectedInstanceId': node['instanceId']})
            assert code == 200
    audit('original-node-resumed', {'release': OLD.name, 'normalExecutorResumed': True})


def repair_ca_constraints():
    """Add mandatory CA key usage without changing any private key or leaf identity."""
    checked_current(NEW)
    operator = BASE / 'operator'
    assert not (operator / 'certificate-strictness-fixed.json').exists()
    write(operator / 'ca.before-strict.crt', (operator / 'ca.crt').read_bytes())
    run(['openssl', 'req', '-new', '-x509', '-key', operator / 'ca.key', '-sha256', '-days', '1825',
        '-subj', '/CN=MyTools adaptation production CA', '-addext', 'basicConstraints=critical,CA:TRUE',
        '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-out', operator / 'ca.strict.crt'])
    leaves = {role: digest(BASE / role / 'tls.crt') for role in ('reader', 'scheduler', 'executor')}
    for role in leaves:
        directory = BASE / role
        run(['openssl', 'verify', '-x509_strict', '-CAfile', operator / 'ca.strict.crt', directory / 'tls.crt'])
        for name in ('tls.p12', 'trust.p12'):
            write(operator / (role + '.' + name + '.before-strict'), (directory / name).read_bytes())
        run(['openssl', 'pkcs12', '-export', '-in', directory / 'tls.crt', '-inkey', directory / 'tls.key',
            '-certfile', operator / 'ca.strict.crt', '-name', role, '-out', directory / 'tls.strict.p12',
            '-passout', 'file:' + str(directory / 'tls.password')])
        run(['keytool', '-importcert', '-noprompt', '-alias', 'production-ca', '-file', operator / 'ca.strict.crt',
            '-keystore', directory / 'trust.strict.p12', '-storetype', 'PKCS12', '-storepass:file', directory / 'tls.password'])
        for name in ('tls', 'trust'):
            path = directory / (name + '.strict.p12')
            os.chown(path, pwd.getpwnam(identity(role)).pw_uid, pwd.getpwnam(identity(role)).pw_gid)
            path.chmod(0o600)
            os.replace(path, directory / (name + '.p12'))
    directory = BASE / 'executor'
    run(['keytool', '-importkeystore', '-noprompt', '-srckeystore', '/etc/ssl/certs/java/cacerts', '-srcstorepass', 'changeit',
        '-destkeystore', directory / 'public-trust.strict.jks', '-deststoretype', 'JKS', '-deststorepass:file', directory / 'tls.password'])
    run(['keytool', '-importcert', '-noprompt', '-alias', 'production-ca', '-file', operator / 'ca.strict.crt',
        '-keystore', directory / 'public-trust.strict.jks', '-storepass:file', directory / 'tls.password'])
    write(directory / 'public-trust.jks', (directory / 'public-trust.strict.jks').read_bytes(), identity('executor'))
    write(operator / 'ca.crt', (operator / 'ca.strict.crt').read_bytes())
    assert leaves == {role: digest(BASE / role / 'tls.crt') for role in leaves}
    run(['systemctl', 'restart', 'mytools-task-scheduler-service'])
    audit('certificate-strictness-fixed', {'strictX509Verified': True, 'privateKeysChanged': False, 'leafIdentitiesChanged': False})


def enable():
    checked_current(NEW)
    assert (BASE / 'operator/activated.json').exists()
    wait_health(23230)
    wait_health(23410)
    wait_health(24102)
    for role, property_name in (('reader', 'reader.adaptation.create-enabled'), ('gateway', 'gateway.chapter-adaptation.create-enabled')):
        data = values(BASE / role / 'application.properties')
        data[property_name] = 'true'
        props(BASE / role / 'application.properties', data, identity(role) if role != 'gateway' else 'mytools')
    run(['systemctl', 'restart', 'mytools-reader-service'])
    wait_health(23230)
    run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    wait_health(23200)
    run(['systemctl', 'enable', 'mytools-adaptation-runtime', 'mytools-adaptation-executor'])
    audit('enabled', {'release': NEW.name, 'createEnabled': True, 'consentStillRequired': True})


def finalize_runtime():
    """Persist the final scoped runtime rules without restarting business services."""
    checked_current(NEW)
    assert (BASE / 'operator/activated.json').exists()
    install_units()
    runtime_start()
    verify_runtime()


def rollback():
    """Restore only this deployment's units and release, retaining data and audit files."""
    current = (ROOT / 'releases/current').resolve(strict=True)
    assert current in (OLD, NEW), 'release_changed'
    with connection('READER') as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM novel_chapter_adaptation WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
        assert cursor.fetchone()[0] == 0, 'active_adaptations_require_drain'
    run(['systemctl', 'stop', 'mytools-adaptation-executor'])
    if current == NEW:
        data = values(BASE / 'scheduler/application.properties')
        data['task.reader-adaptation-deployment.active'] = 'false'
        props(BASE / 'scheduler/application.properties', data, identity('scheduler'))
        # 由新 Scheduler 自身关闭模板；服务无法启动时仍先恢复旧业务服务。
        subprocess.run(['systemctl', 'restart', 'mytools-task-scheduler-service'], capture_output=True, timeout=100)
    for service in SERVICES:
        path = Path('/etc/systemd/system/mytools-' + service + '.service.d/adaptation-production.conf')
        if path.exists():
            os.replace(path, BASE / 'operator' / (service + '.rolled-back.conf'))
    temporary = ROOT / 'releases/.adaptation-production-rollback'
    assert not temporary.exists()
    temporary.symlink_to(OLD.name)
    os.replace(temporary, ROOT / 'releases/current')
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'restart', 'mytools-task-scheduler-service'])
    run(['systemctl', 'restart', 'mytools-task-executor-service'])
    wait_health(23410)
    for service, port in (('reader-service', 23230), ('mytools-gateway', 23200)):
        run(['systemctl', 'restart', 'mytools-' + service])
        wait_health(port)
    run(['systemctl', 'stop', 'mytools-adaptation-runtime'])
    audit('rolled-back', {'release': OLD.name, 'productionDataRemoved': False, 'newRuntimeStopped': True})


if __name__ == '__main__':
    os.umask(0o077)
    actions = {'stage': stage, 'activate': activate, 'runtime-start': runtime_start, 'enable': enable,
        'runtime-verify': verify_runtime, 'rollback': rollback, 'resume-original-node': resume_original_node,
        'continue-activation': complete_activation, 'repair-ca-constraints': repair_ca_constraints,
        'finalize-runtime': finalize_runtime}
    try:
        assert os.geteuid() == 0 and len(sys.argv) == 2 and sys.argv[1] in actions
        actions[sys.argv[1]]()
    except Exception as error:
        print(json.dumps({'operation': sys.argv[1] if len(sys.argv) == 2 else 'invalid', 'errorType': type(error).__name__,
            'errno': getattr(error, 'errno', None), 'line': traceback.extract_tb(error.__traceback__)[-1].lineno,
            'reason': str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'private_diagnostic_suppressed'}), flush=True)
        raise SystemExit(1)
