"""APP 下载入口：复用消息批次与托管文件归档链路。"""
import base64
from datetime import UTC, datetime
import hashlib
import json
import math
import mimetypes
import os
from pathlib import Path
import re
import sqlite3
from threading import RLock
import time
from urllib.parse import urlsplit, unquote
from uuid import UUID, uuid4
from weakref import WeakValueDictionary
from .models import CreateDownloadRequest
from .service import TERMINAL_STATUSES

MAX_BYTES = 512 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024
UPLOAD_LIFETIME_SECONDS = 86400
MAX_HISTORY_PAGE_SIZE = 50
SQLITE_LOOKUP_BATCH = 500
MEDIA = {'.jpg':'image/jpeg', '.jpeg':'image/jpeg', '.png':'image/png', '.gif':'image/gif',
         '.webp':'image/webp', '.heic':'image/heic', '.heif':'image/heif', '.avif':'image/avif',
         '.mp4':'video/mp4', '.mov':'video/quicktime', '.webm':'video/webm', '.mkv':'video/x-matroska'}


class AppDownloadTools:
    """在私有目录持久化上传状态，服务重启后可继续提交。"""
    def __init__(self, service, root: Path, storage):
        self.service, self.root, self.storage = service, root, storage
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = RLock()
        self._entry_locks = WeakValueDictionary()
        self.db = sqlite3.connect(root / 'uploads.sqlite3', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute('CREATE TABLE IF NOT EXISTS entry (id TEXT PRIMARY KEY, owner INTEGER NOT NULL, key TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, created REAL NOT NULL, request_id TEXT, UNIQUE(owner,key))')
        self.db.execute('CREATE INDEX IF NOT EXISTS entry_owner_created_id ON entry(owner, created DESC, id DESC)')
        self.db.execute('CREATE INDEX IF NOT EXISTS entry_owner_request ON entry(owner, request_id)')
        self.db.commit()

    def _entry(self, owner, identifier):
        identifier = str(UUID(identifier))
        with self.lock:
            row = self.db.execute('SELECT * FROM entry WHERE id=? AND owner=?', (identifier, owner)).fetchone()
        if row is None:
            raise KeyError('entry_not_found')
        return row

    def _entry_lock(self, identifier):
        """只串行化同一条目的变更，SQLite 全局锁不会等待条目锁。"""
        identifier = str(UUID(identifier))
        with self.lock:
            return self._entry_locks.setdefault(identifier, RLock())

    def _create(self, owner, key, kind, payload):
        if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', key):
            raise ValueError('invalid_idempotency_key')
        encoded = json.dumps(payload, sort_keys=True, ensure_ascii=True)
        row = self.db.execute('SELECT * FROM entry WHERE owner=? AND key=?', (owner,key)).fetchone()
        if row:
            if row['kind'] != kind or row['payload'] != encoded:
                raise ValueError('idempotency_conflict')
            return row
        identifier = str(uuid4())
        self.db.execute('INSERT INTO entry(id,owner,key,kind,payload,created) VALUES(?,?,?,?,?,?)',
                        (identifier,owner,key,kind,encoded,time.time()))
        self.db.commit()
        return self._entry(owner,identifier)

    def links(self, owner, username, body):
        text = body.get('text')
        if not isinstance(text,str) or not text.strip() or len(text)>20000:
            raise ValueError('invalid_link_text')
        urls = list(dict.fromkeys(re.findall(r'https?://[^\s<>"\u3000]+', text)))
        magnets=re.findall(r'magnet:\?[^\s<>"\u3000]+',text)
        if magnets:
            if urls or len(magnets)!=1: raise ValueError('send_one_magnet_per_message')
            from .mcp_server import classify
            kind,params,strategy=classify(magnets[0],'auto')
            if strategy=='LOCAL': kind='LOCAL_MAGNET'
            else:
                account=os.getenv('DOWNLOAD_MCP_PIKPAK_ACCOUNT_ID','')
                if not account: raise ValueError('magnet_account_unavailable')
                params['accountId']=account
            with self.lock:
                row=self._create(owner,body.get('idempotencyKey'),'LINKS',{'text':text,'username':username})
            with self._entry_lock(row['id']):
                # 磁链使用既有独立任务契约，不传入消息批次专属字段。
                return self._submit(self._entry(owner,row['id']),kind,params)
        if not urls:
            with self.lock:
                return self._view(self._create(owner,body.get('idempotencyKey'),'TEXT',{'text':text,'username':username}))
        if len(urls)>20: raise ValueError('too_many_links')
        items=[]
        for index,url in enumerate(urls):
            url=url.rstrip('。，；、！）)]}')
            parsed=urlsplit(url)
            if not parsed.hostname or parsed.username or parsed.password or len(url)>4096:
                raise ValueError('invalid_link')
            name=Path(unquote(parsed.path)).name
            name=re.sub(r'[/\\\x00-\x1f]', '_', name)[:180]
            if not name or name in {'.','..'}: name=f'download-{index+1}.bin'
            items.append({'url':url,'fileName':name})
        with self.lock:
            row=self._create(owner,body.get('idempotencyKey'),'LINKS',{'items':items,'text':text,'username':username})
        with self._entry_lock(row['id']):
            row=self._entry(owner,row['id'])
            params={'items':items,'messageBatchId':row['id'],'receivedAt':self._received(row),
                    'resourceUsername':username,'albumTitleText':text[:500]}
            return self._submit(row,'MESSAGE_URL_BATCH',params)

    def upload(self, owner, username, body):
        name=body.get('name'); size=body.get('bytes')
        if not isinstance(name,str) or not name or len(name)>180 or name in {'.','..'} or re.search(r'[/\\\x00-\x1f]',name):
            raise ValueError('invalid_file_name')
        mime=MEDIA.get(Path(name).suffix.lower()) or mimetypes.guess_type(name)[0] or 'application/octet-stream'
        if not mime or type(size) is not int or not 0<size<=MAX_BYTES:
            raise ValueError('invalid_media_or_size')
        self._expire_uploads()
        with self.lock:
            active=self.db.execute("SELECT count(*) FROM entry WHERE owner=? AND kind='MEDIA' AND request_id IS NULL AND created>?",(owner,time.time()-UPLOAD_LIFETIME_SECONDS)).fetchone()[0]
            if active>=10 and not self.db.execute('SELECT 1 FROM entry WHERE owner=? AND key=?',(owner,body.get('idempotencyKey'))).fetchone():
                raise ValueError('upload_limit')
            row=self._create(owner,body.get('idempotencyKey'),'MEDIA',{'name':name,'bytes':size,'mime':mime,'username':username})
        with self._entry_lock(row['id']):
            return self._upload_view(self._entry(owner,row['id']))

    def _expire_uploads(self):
        with self.lock:
            rows=self.db.execute("SELECT id FROM entry WHERE kind='MEDIA' AND created<?",(time.time()-UPLOAD_LIFETIME_SECONDS,)).fetchall()
        for row in rows:
            lock=self._entry_lock(row['id'])
            # 到期清理跳过仍在上传或归档的文件，后续请求会再次清理。
            if lock.acquire(blocking=False):
                try:
                    (self.root / (row['id']+'.bin')).unlink(missing_ok=True)
                finally:
                    lock.release()

    def _upload_view(self,row):
        path=self.root/(row['id']+'.bin')
        return {'id':row['id'],'bytesReceived':path.stat().st_size if path.exists() else 0,
                'chunkBytes':CHUNK_BYTES,'requestId':row['request_id']}

    def chunk(self,owner,identifier,offset,data):
        with self._entry_lock(identifier):
            row=self._entry(owner,identifier); self._live_upload(row)
            meta=json.loads(row['payload']); path=self.root/(row['id']+'.bin')
            if not data or len(data)>CHUNK_BYTES or offset<0 or offset+len(data)>meta['bytes']:
                raise ValueError('invalid_chunk')
            size=path.stat().st_size if path.exists() else 0
            if offset<size:
                with path.open('rb') as f:
                    f.seek(offset)
                    if f.read(len(data)) != data: raise ValueError('chunk_conflict')
            elif offset==size:
                with path.open('ab') as f: f.write(data)
            else: raise ValueError('chunk_offset')
            return self._upload_view(row)

    def _live_upload(self,row):
        if row['kind']!='MEDIA' or row['created']<time.time()-UPLOAD_LIFETIME_SECONDS or row['request_id']:
            raise ValueError('upload_not_writable')

    def complete(self,owner,identifier):
        with self._entry_lock(identifier):
            row=self._entry(owner,identifier)
            if row['request_id']: return self._view(row)
            self._live_upload(row)
            meta=json.loads(row['payload']); path=self.root/(row['id']+'.bin')
            if not path.exists() or path.stat().st_size!=meta['bytes']: raise ValueError('incomplete_upload')
            with path.open('rb') as f:
                head=f.read(32)
                if meta['mime'].startswith(('image/','video/')) and not self._media_signature(head,meta['mime']): raise ValueError('invalid_media_content')
                f.seek(0); digest=hashlib.file_digest(f,'sha256').hexdigest()
            uri=self.storage.publish(path,'managed',f'app-download-inputs/{owner}/{row["id"]}/source.bin',
                                     f'app-upload:{row["id"]}',meta['bytes'],digest)
            # 仅使用服务端生成的托管地址，不接受 APP 指定其他用户的文件路径。
            params={'itemId':row['id'],'sourceStorageUri':uri,'fileName':meta['name'],
                    'expectedSha256':digest,'maxBytes':meta['bytes'],'assetMimeType':meta['mime'],
                    'resourceUsername':meta['username'],'receivedAt':self._received(row)}
            if Path(meta['name']).suffix.lower() in {'.zip','.rar','.7z'}:
                params={key:value for key,value in params.items() if key not in {'itemId','assetMimeType','receivedAt'}}
                result=self._submit(row,'LOCAL_IMPORT',params)
            else:
                result=self._submit(row,'STORAGE_OBJECT',params)
            path.unlink(missing_ok=True)
            return result

    @staticmethod
    def _media_signature(head,mime):
        if mime=='image/png': return head.startswith(b'\x89PNG\r\n\x1a\n')
        if mime=='image/jpeg': return head.startswith(b'\xff\xd8\xff')
        if mime=='image/gif': return head[:6] in (b'GIF87a',b'GIF89a')
        if mime=='image/webp': return head[:4]==b'RIFF' and head[8:12]==b'WEBP'
        if mime in ('video/webm','video/x-matroska'): return head.startswith(b'\x1aE\xdf\xa3')
        return len(head)>=12 and head[4:8]==b'ftyp'

    @staticmethod
    def _received(row):
        return datetime.fromtimestamp(row['created'],UTC).isoformat()

    def _submit(self,row,kind,params):
        if row['request_id'] or row['created']==0: return self._view(row)
        command=CreateDownloadRequest(f'app-tool:{row["owner"]}:{row["key"]}','APP_TOOL',row['id'],kind,params,row['owner'])
        result=self.service.create(command,defer_unavailable_scheduler=True)
        with self.lock:
            self.db.execute('UPDATE entry SET request_id=? WHERE id=?',(str(result.id),row['id']))
            self.db.commit()
        return self._view(self._entry(row['owner'],row['id']),snapshot=result.task_instance_id is None)

    def _view(self,row,snapshot=False):
        reader = self.service.result_snapshot_for_owner if snapshot else self.service.result_summary_for_owner
        summary=reader(UUID(row['request_id']),row['owner']) if row['request_id'] else None
        payload=json.loads(row['payload'])
        return {'id':row['id'],'requestId':row['request_id'],'kind':row['kind'],
                'name':payload.get('name') or payload.get('text','')[:120],
                'result':summary,'status':summary['status'] if summary else 'NO_MATCH' if row['kind']=='TEXT' else 'FAILED' if row['kind']=='LINKS' else ('EXPIRED' if row['created']<time.time()-UPLOAD_LIFETIME_SECONDS else 'UPLOADING'),
                'createdAt':self._received(row)}

    def history(self,owner):
        with self.lock:
            rows = self.db.execute('SELECT * FROM entry WHERE owner=? ORDER BY created DESC LIMIT 30',(owner,)).fetchall()
        # SQLite 连接读取结束后释放锁，网络与结果构造不能阻塞上传和取消。
        return [self._view(row,snapshot=True) for row in rows]

    def history_page(self, owner: int, limit: int, before: str | None = None) -> dict:
        """返回最近记录和未完成记录的并集，或游标之前的历史。"""
        if type(limit) is not int or not 1 <= limit <= MAX_HISTORY_PAGE_SIZE:
            raise ValueError('invalid_history_limit')
        boundary = self._decode_history_cursor(owner, before) if before is not None else None
        request_ids = self.service.unfinished_app_request_ids(owner) if boundary is None else []
        with self.lock:
            if boundary is None:
                rows = self.db.execute(
                    'SELECT * FROM entry WHERE owner=? ORDER BY created DESC, id DESC LIMIT ?',
                    (owner, limit + 1)).fetchall()
                unfinished = self._unfinished_history_rows(owner, request_ids)
            else:
                rows = self.db.execute(
                    '''SELECT * FROM entry WHERE owner=? AND (created, id) < (?, ?)
                       ORDER BY created DESC, id DESC LIMIT ?''',
                    (owner, *boundary, limit + 1)).fetchall()
                unfinished = []
        recent = rows[:limit]
        # 游标只取普通页末尾，额外补入的老任务不会跳过其前面的历史。
        cursor = self._history_cursor(owner, recent[-1]) if len(rows) > limit else None
        recent_ids = {row['id'] for row in recent}
        entries = {row['id']: row for row in [*unfinished, *recent]}
        views = []
        for row in sorted(entries.values(), key=lambda value: (value['created'], value['id']), reverse=True):
            view = self._view(row, snapshot=True)
            if (row['id'] in recent_ids or view['status'] == 'UPLOADING'
                    or (view['result'] is not None and view['status'] not in TERMINAL_STATUSES)):
                views.append(view)
        return {'items': views, 'nextCursor': cursor}

    def _unfinished_history_rows(self, owner: int, request_ids: list[str]) -> list[sqlite3.Row]:
        rows = self.db.execute(
            """SELECT * FROM entry WHERE owner=? AND request_id IS NULL AND kind='MEDIA' AND created>=?""",
            (owner, time.time() - UPLOAD_LIFETIME_SECONDS)).fetchall()
        for start in range(0, len(request_ids), SQLITE_LOOKUP_BATCH):
            batch = request_ids[start:start + SQLITE_LOOKUP_BATCH]
            placeholders = ','.join('?' for _ in batch)
            rows.extend(self.db.execute(
                f'SELECT * FROM entry WHERE owner=? AND request_id IN ({placeholders})',
                (owner, *batch)).fetchall())
        return rows

    @staticmethod
    def _history_cursor(owner: int, row: sqlite3.Row) -> str:
        value = f"{owner}:{float(row['created']).hex()}:{row['id']}"
        return base64.urlsafe_b64encode(value.encode('ascii')).decode('ascii').rstrip('=')

    @staticmethod
    def _decode_history_cursor(owner: int, before: str) -> tuple[float, str]:
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', before):
            raise ValueError('invalid_history_cursor')
        try:
            value = base64.urlsafe_b64decode(before + '=' * (-len(before) % 4)).decode('ascii')
            cursor_owner, timestamp, identifier = value.split(':')
            created = float.fromhex(timestamp)
            if cursor_owner != str(owner) or not math.isfinite(created) or created < 0 \
                    or str(UUID(identifier)) != identifier:
                raise ValueError('invalid_history_cursor')
            return created, identifier
        except (ValueError, OverflowError) as exception:
            raise ValueError('invalid_history_cursor') from exception

    def detail(self,owner,identifier):
        return self._view(self._entry(owner,identifier))

    def cancel(self,owner,identifier):
        with self._entry_lock(identifier):
            row=self._entry(owner,identifier)
            if row['request_id']: self.service.cancel_for_owner(UUID(row['request_id']),owner)
            else:
                (self.root/(row['id']+'.bin')).unlink(missing_ok=True)
                with self.lock:
                    self.db.execute('UPDATE entry SET created=0 WHERE id=?',(row['id'],))
                    self.db.commit()
            return self._view(self._entry(owner,identifier))
