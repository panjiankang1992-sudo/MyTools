"""只读教材修订与发布基线；凭据仅在服务器内存和受限临时文件中使用。"""
from pathlib import Path
import json
import os
import shlex
import subprocess
import sys
import tempfile

config = {}
for raw in Path('/opt/yuyutian/toolbox/config/toolbox.env').read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#') or '=' not in line:
        continue
    key, value = line.split('=', 1)
    if key.startswith('TOOLBOX_DB_'):
        config[key] = ' '.join(shlex.split(value, comments=False))


def option(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') + '"'


fd, filename = tempfile.mkstemp(prefix='toolbox-readonly-', dir='/run')
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write('[client]\n')
        for key, value in [('host', config.get('TOOLBOX_DB_HOST', '127.0.0.1')), ('port', config.get('TOOLBOX_DB_PORT', '3306')), ('user', config.get('TOOLBOX_DB_USER', 'mytools_toolbox')), ('password', config.get('TOOLBOX_DB_PASSWORD', ''))]:
            stream.write(key + '=' + option(value) + '\n')
    query = '''
    SELECT t.edition_id, t.current_version_id, d.base_content_version, d.revision,
      JSON_LENGTH(d.editable_json, '$.entries') AS draft_entries,
      t.updated_at AS textbook_updated, d.updated_at AS draft_updated
    FROM toolbox_textbook t LEFT JOIN toolbox_textbook_draft d ON d.draft_id=t.draft_id
    ORDER BY t.edition_id;
    SELECT action, edition_id, from_revision, to_revision, from_version, to_version, created_at
    FROM toolbox_curriculum_audit ORDER BY id DESC LIMIT 30;
    '''
    snapshot = len(sys.argv) > 1 and sys.argv[1] == 'snapshot'
    if snapshot:
        query = '''SELECT JSON_OBJECT('editionId', t.edition_id, 'revision', d.revision,
          'baseline', d.base_content_version, 'currentVersionId', t.current_version_id,
          'draft', d.editable_json, 'draftSourceMetadata', d.source_metadata_json,
          'baseBook', CONVERT(b.content_bytes USING utf8mb4), 'baseMetadata', b.edition_metadata_json,
          'currentBook', CONVERT(c.content_bytes USING utf8mb4), 'currentMetadata', c.edition_metadata_json)
        FROM toolbox_textbook t JOIN toolbox_textbook_draft d ON d.draft_id=t.draft_id
        JOIN toolbox_textbook_version b ON b.version_id=d.base_content_version
        JOIN toolbox_textbook_version c ON c.version_id=t.current_version_id
        WHERE t.edition_id='en-yl-4-1-yilin-2024';'''
    command = ['mysql', '--defaults-extra-file=' + filename, '--batch', '--raw', *(['--skip-column-names'] if snapshot else []), config.get('TOOLBOX_DB_NAME', 'mytools_toolbox'), '-e', query]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if result.returncode:
        raise RuntimeError('Readonly diagnostic failed; database credentials withheld')
    print(result.stdout.decode())
finally:
    os.unlink(filename)
