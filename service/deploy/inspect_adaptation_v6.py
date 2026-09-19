#!/usr/bin/env python3
"""Read bounded acceptance metadata; never emit source, prompts or credentials."""
import importlib.util
import json
import hashlib
from pathlib import Path

BASE = Path('/opt/yuyutian/mytools/runtime/adaptation-production-20260911')
spec = importlib.util.spec_from_file_location('deployment', BASE / 'operator/deploy.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

with d.connection('READER') as db, db.cursor() as cursor:
    cursor.execute('''SELECT id,revision_number,request_kind,status,current_stage,last_error_code,original_content_sha256,
        consent_policy,prompt_version,style_template_snapshot_json FROM novel_chapter_adaptation
        WHERE owner_id=%s AND shelf_book_id=%s ORDER BY created_at DESC LIMIT 6''',
        (2054944045960138752, '6245915b-a9e5-4142-89f9-96d94024829d'))
    rows = cursor.fetchall()
    result = []
    for row in rows:
        if row[1] < 4:
            continue
        cursor.execute('''SELECT call_kind,status,transmission_count,error_code,http_status
            FROM novel_chapter_adaptation_attempt WHERE adaptation_id=%s ORDER BY attempt_no''', (row[0],))
        attempts = cursor.fetchall()
        style = json.loads(row[9]) if row[9] else None
        result.append({'id': row[0], 'version': row[1], 'kind': row[2], 'status': row[3], 'stage': row[4],
            'error': row[5], 'sourceSha256': row[6], 'consentPolicy': row[7], 'promptVersion': row[8],
            'style': style['template']['code'] if style else None, 'attempts': attempts})
    cursor.execute("SELECT COUNT(*) FROM novel_chapter_adaptation WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    active = cursor.fetchone()[0]
print(json.dumps({'versions': result, 'active': active}))

# 仅在本轮任务全部结束后回读接口，正文只在服务器内存核验，不输出。
if active == 0:
    def api(path):
        status, value = d.request(23230, '/api/v1/reader-state' + path + '?ownerId=2054944045960138752')
        assert status == 200
        return value

    if result:
        details = []
        for row in result:
            if row['version'] < 5 or row['status'] != 'COMPLETED':
                continue
            detail = api('/chapter-adaptations/' + row['id'])
            content = detail['result']['content']
            digest = hashlib.sha256(content.encode()).hexdigest()
            assert digest == detail['result']['contentSha256']
            details.append({'version': row['version'], 'codepoints': len(content), 'sha256': digest,
                'lineage': detail['version']['lineage']})
        original = api('/shelves/6245915b-a9e5-4142-89f9-96d94024829d/chapters/7f28daf5-7ddf-4314-80d8-47b389096045/content')
        assert hashlib.sha256(original['text'].encode()).hexdigest() == original['sha256']
        assert original['sha256'] == '069fa1a6f13b199435c40355a03634c5ad0f0403ebbaf2815246401e0abcd2b2'
        print(json.dumps({'persistedResults': details, 'originalUnchanged': True}))
