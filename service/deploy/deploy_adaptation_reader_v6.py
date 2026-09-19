#!/usr/bin/env python3
"""Deploy one immutable Reader hotfix under the shared cross-session deployment lock."""
import fcntl
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path('/opt/yuyutian/mytools')
BASE = ROOT / 'runtime/adaptation-production-20260911'
UPLOAD = Path('/tmp/mytools-adaptation-reader-v6-20260913/reader-service.jar')
TARGET = BASE / 'reader/artifacts/reader-v6-b53d77875674.jar'
EXPECTED = 'b53d77875674ab8ca8f82720638c96bcd16f41e8fbe4fd0bb63df74139e1a24c'
AUDIT = BASE / 'operator/reader-v6-20260913'
UNIT = Path('/etc/systemd/system/mytools-reader-service.service.d/adaptation-production.conf')
spec = importlib.util.spec_from_file_location('deployment', BASE / 'operator/deploy.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)


def migrations(path):
    with zipfile.ZipFile(path) as archive:
        return {n: archive.read(n) for n in archive.namelist() if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql')}


def run():
    with (ROOT / 'runtime/deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print('DEPLOYMENT_LOCK_ACQUIRED', flush=True)
        assert not AUDIT.exists(), 'already_staged'
        assert d.digest(UPLOAD) == EXPECTED, 'upload_mismatch'
        original_unit = UNIT.read_text()
        old = ROOT / 'releases/chapter-adaptation-production-20260913-v5/apps/reader-service.jar'
        assert original_unit.count(str(old)) == 1, 'reader_changed'
        assert migrations(old) == migrations(UPLOAD), 'unexpected_migration_change'
        original_current = (ROOT / 'releases/current').readlink()
        configs = {role: (BASE / role / 'application.properties').read_bytes() for role in ('reader', 'gateway')}
        assert all(d.values(BASE / role / 'application.properties').get(key) == 'false' for role, key in
            [('reader', 'reader.adaptation.create-enabled'), ('gateway', 'gateway.chapter-adaptation.create-enabled')]), 'creation_not_disabled'
        with d.connection('READER') as db, db.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM novel_chapter_adaptation WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
            assert cursor.fetchone()[0] == 0, 'active_adaptations'
        AUDIT.mkdir(mode=0o700)
        d.write(AUDIT / 'reader.unit.before', original_unit)
        for role, raw in configs.items():
            d.write(AUDIT / (role + '.properties.before'), raw)
        TARGET.parent.mkdir(mode=0o750, exist_ok=True)
        shutil.chown(TARGET.parent, user=d.identity('reader'), group='mytools')
        assert not TARGET.exists(), 'immutable_artifact_exists'
        d.write(TARGET, UPLOAD.read_bytes(), d.identity('reader'), mode=0o400)
        try:
            assert UNIT.read_text() == original_unit, 'reader_unit_changed'
            d.write(UNIT, original_unit.replace(str(old), str(TARGET)), mode=0o644)
            data = d.values(BASE / 'reader/application.properties')
            data['reader.adaptation.create-enabled'] = 'true'
            d.props(BASE / 'reader/application.properties', data, d.identity('reader'))
            d.run(['systemctl', 'daemon-reload'])
            d.run(['systemctl', 'restart', 'mytools-reader-service'])
            d.wait_health(23230)
            d.wait_health(24231, True)
            d.wait_health(24102)
            # 只恢复既有改编开关，不修改另一会话的 Gateway JAR 或 image drop-in。
            assert (BASE / 'gateway/application.properties').read_bytes() == configs['gateway'], 'gateway_config_changed'
            data = d.values(BASE / 'gateway/application.properties')
            data['gateway.chapter-adaptation.create-enabled'] = 'true'
            d.props(BASE / 'gateway/application.properties', data, 'mytools')
            d.run(['systemctl', 'restart', 'mytools-mytools-gateway'])
            d.wait_health(23200)
            assert (ROOT / 'releases/current').readlink() == original_current, 'global_release_changed'
            result = {'readerSha256': EXPECTED, 'globalReleaseUnchanged': True, 'migrationsUnchanged': True,
                'createEnabled': True, 'healthy': True, 'otherServicesRestarted': False}
            d.write(AUDIT / 'result.json', json.dumps(result))
            print(json.dumps(result), flush=True)
        except Exception:
            d.write(UNIT, original_unit, mode=0o644)
            for role, raw in configs.items():
                d.write(BASE / role / 'application.properties', raw, d.identity(role) if role == 'reader' else 'mytools')
            d.run(['systemctl', 'daemon-reload'])
            d.run(['systemctl', 'restart', 'mytools-reader-service', 'mytools-mytools-gateway'])
            raise


if __name__ == '__main__':
    try:
        run()
    except Exception as error:
        print(json.dumps({'failed': type(error).__name__, 'reason': str(error) if isinstance(error, AssertionError) else 'inspect_private_diagnostics'}))
        sys.exit(1)
