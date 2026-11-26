#!/usr/bin/env python3
"""修复首次部署的 PDF JDBC 会话时区，校正既有截止时间并保留私有回滚记录。"""
import json
from pathlib import Path
import pymysql
import subprocess
import zipfile
import deploy_pdf_tools as d


def main():
    """停止 PDF 写入后校正时间，仅替换独立服务配置资源。"""
    root = d.ROOT / 'releases/pdf-tools-20260926-v4'
    root.mkdir(exist_ok=False)
    old = d.RELEASE / 'apps/pdf-service.jar'
    jar = root / 'pdf-service.jar'
    with zipfile.ZipFile(old) as source, zipfile.ZipFile(jar, 'w') as target:
        for item in source.infolist():
            data = Path('/tmp/pdf-utc-application.yml').read_bytes() if item.filename == 'BOOT-INF/classes/application.yml' else source.read(item.filename)
            target.writestr(item, data)
    root.chmod(0o755)
    jar.chmod(0o644)
    config = d.values(d.CONFIG / 'service.env')
    assert all(n.get('runningTasks', 0) == 0 for n in d.request('/api/v1/execution-topology/nodes') if n['name'] == 'pdf-executor-1')
    unit = Path('/etc/systemd/system/mytools-pdf-service.service')
    previous = unit.read_text()
    assert str(old) in previous
    d.command('systemctl', 'stop', 'mytools-pdf-service')
    changed = []
    try:
        with pymysql.connect(host='127.0.0.1', user='mytools_pdf', password=config['PDF_DB_PASSWORD'], database='mytools_pdf') as db, db.cursor() as cursor:
            cursor.execute('SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW())')
            offset = cursor.fetchone()[0]
            assert offset == 28800, 'unexpected_original_session_offset'
            cursor.execute("SET time_zone='+00:00'")
            cursor.execute('SELECT id,expires_at FROM pdf_record')
            changed = cursor.fetchall()
            d.private(d.CONFIG / 'timezone-backup.json', json.dumps({'offsetSeconds': offset, 'records': [[i, str(t)] for i, t in changed]}, indent=2))
            # 原服务把 UTC 日期写入了 UTC+8 会话，因此修正物理截止时刻；不复活 EXPIRED 对象。
            cursor.execute('UPDATE pdf_record SET expires_at=TIMESTAMPADD(SECOND,%s,expires_at)', (offset,))
            db.commit()
        unit.write_text(previous.replace(str(old), str(jar)))
        d.command('systemctl', 'daemon-reload')
        d.command('systemctl', 'start', 'mytools-pdf-service')
        d.health(23342)
    except Exception:
        subprocess.run(['systemctl', 'stop', 'mytools-pdf-service'], check=False)
        if changed:
            with pymysql.connect(host='127.0.0.1', user='mytools_pdf', password=config['PDF_DB_PASSWORD'], database='mytools_pdf') as db, db.cursor() as cursor:
                cursor.execute("SET time_zone='+00:00'")
                cursor.executemany('UPDATE pdf_record SET expires_at=%s WHERE id=%s', [(t, i) for i, t in changed])
                db.commit()
        unit.write_text(previous)
        d.command('systemctl', 'daemon-reload')
        d.command('systemctl', 'start', 'mytools-pdf-service')
        raise
    print(json.dumps({'pdfServiceRelease': str(root), 'sessionTimeZone': 'UTC', 'correctedExpiryRows': len(changed), 'expiredRecordsReactivated': False}))


if __name__ == '__main__':
    main()
