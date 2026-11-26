"""下载聊天复用编排、所有权与上传边界回归。"""
import hashlib
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event
from unittest.mock import patch
from uuid import UUID, uuid4
import unittest
from mytools_download_ingestion.app_tools import AppDownloadTools
from mytools_download_ingestion.models import CreateDownloadRequest, DownloadStatus
from mytools_download_ingestion.service import DownloadRequestService, InMemoryDownloadRequestRepository

class Scheduler:
    def __init__(self): self.calls=[]
    def create_task(self,**args): self.calls.append(args);return uuid4()
    def get_task(self,id): return {'status':'RUNNING'}
    def cancel_task(self,id): return {'status':'CANCELLED'}

class Storage:
    def __init__(self): self.calls=[]
    def publish(self,path,root,name,key,size,digest):
        assert path.stat().st_size==size and hashlib.sha256(path.read_bytes()).hexdigest()==digest
        self.calls.append((root,name,key));return 'storage://managed/'+name

class AppToolsTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.scheduler=Scheduler();self.storage=Storage()
        self.repository=InMemoryDownloadRequestRepository()
        self.service=DownloadRequestService(self.repository,self.scheduler)
        self.tools=AppDownloadTools(self.service,Path(self.tmp.name),self.storage)
    def tearDown(self): self.tools.db.close();self.tmp.cleanup()
    def test_links_share_message_batch_and_are_idempotent(self):
        body={'text':'album\nhttps://example.com/a.jpg\nhttps://x.com/demo/status/123','idempotencyKey':'one'}
        one=self.tools.links(1,'demo',body);two=self.tools.links(1,'demo',body)
        self.assertEqual(one['id'],two['id']);self.assertEqual(len(self.scheduler.calls),1)
        call=self.scheduler.calls[0];self.assertEqual(call['task_name'],'download_message_url_batch')
        self.assertEqual(call['parameters']['resourceUsername'],'demo');self.assertEqual(call['parameters']['ownerId'],1)
        with self.assertRaises(ValueError): self.tools.links(1,'demo',{**body,'text':'https://example.com/other'})
    def test_uploaded_image_skips_external_downloader(self):
        data=b'\x89PNG\r\n\x1a\n'+b'x'*100
        row=self.tools.upload(1,'demo',{'name':'sample.png','bytes':len(data),'idempotencyKey':'media'})
        self.tools.chunk(1,row['id'],0,data);self.tools.chunk(1,row['id'],0,data)
        done=self.tools.complete(1,row['id']);self.tools.complete(1,row['id'])
        self.assertEqual(len(self.scheduler.calls),1);self.assertEqual(done['status'],'RUNNING')
        call=self.scheduler.calls[0];self.assertEqual(call['task_name'],'download_storage_object')
        self.assertNotIn('url',call['parameters']);self.assertEqual(call['parameters']['assetMimeType'],'image/png')
        self.assertEqual(len(self.storage.calls),1)

    def test_slow_upload_commit_does_not_block_other_owner_or_duplicate_submission(self):
        data=b'\x89PNG\r\n\x1a\n'+b'x'*100
        row=self.tools.upload(1,'demo',{'name':'sample.png','bytes':len(data),'idempotencyKey':'media'})
        self.tools.chunk(1,row['id'],0,data)
        publish_started=Event()
        publish_released=Event()
        publish=self.storage.publish

        def slow_publish(*args):
            publish_started.set()
            if not publish_released.wait(5):
                raise TimeoutError('test did not release publish')
            return publish(*args)

        self.storage.publish=slow_publish
        with ThreadPoolExecutor(max_workers=3) as pool:
            uploading=pool.submit(self.tools.complete,1,row['id'])
            try:
                self.assertTrue(publish_started.wait(2))
                duplicate=pool.submit(self.tools.complete,1,row['id'])
                link=pool.submit(self.tools.links,2,'other',{'text':'https://example.com/a.jpg','idempotencyKey':'other'})
                self.assertEqual('RUNNING',link.result(timeout=2)['status'])
                self.assertFalse(duplicate.done())
            finally:
                publish_released.set()
            self.assertEqual(uploading.result(timeout=2)['requestId'],duplicate.result(timeout=2)['requestId'])
        self.assertEqual(1,len(self.storage.calls))
        self.assertEqual(2,len(self.scheduler.calls))

    def test_scheduler_outage_keeps_app_entry_linked_to_durable_request(self):
        from urllib.error import URLError
        from unittest.mock import patch
        repository=InMemoryDownloadRequestRepository(background_enabled=True)
        self.tools.service=DownloadRequestService(repository,self.scheduler)
        body={'text':'https://example.com/a.jpg','idempotencyKey':'deferred'}
        with patch.object(self.scheduler,'create_task',side_effect=[URLError('unavailable'),uuid4()]) as create:
            entry=self.tools.links(1,'demo',body)
            self.assertEqual('ACCEPTED',entry['status'])
            request=repository.find_by_idempotency_key('app-tool:1:deferred')
            self.assertEqual(str(request.id),entry['requestId'])
            self.tools.service.create(CreateDownloadRequest(request.idempotency_key,request.source_type,
                request.source_key,request.request_kind,request.parameters,request.owner_id))
            self.assertEqual(create.call_args_list[0],create.call_args_list[1])
        replay=self.tools.links(1,'demo',body)
        self.assertEqual(entry['requestId'],replay['requestId'])
        self.assertEqual('RUNNING',replay['status'])
    def test_magnet_uses_existing_strict_task_contract(self):
        from unittest.mock import patch
        with patch.dict('os.environ', {'DOWNLOAD_MCP_PIKPAK_ACCOUNT_ID':'00000000-0000-4000-8000-000000000001','DOWNLOAD_MCP_MAGNET_MODE':'pikpak'}):
            self.tools.links(1,'demo',{'text':'magnet:?xt=urn:btih:'+'a'*40,'idempotencyKey':'magnet'})
        call=self.scheduler.calls[0]
        self.assertEqual(call['task_name'],'download_pikpak_magnet')
        self.assertEqual(set(call['parameters']),{'magnetUri','accountId','ownerId','downloadRequestId'})

    def test_owner_offset_size_and_signature_boundaries(self):
        row=self.tools.upload(1,'demo',{'name':'sample.png','bytes':8,'idempotencyKey':'m'})
        with self.assertRaises(KeyError): self.tools.chunk(2,row['id'],0,b'bad')
        with self.assertRaises(KeyError): self.tools.detail(2,row['id'])
        with self.assertRaises(ValueError): self.tools.chunk(1,row['id'],2,b'bad')
        with self.assertRaises(ValueError): self.tools.complete(1,row['id'])
        self.tools.chunk(1,row['id'],0,b'notimage')
        with self.assertRaises(ValueError): self.tools.chunk(1,row['id'],0,b'changed!')
        with self.assertRaises(ValueError): self.tools.complete(1,row['id'])
        self.assertEqual(self.scheduler.calls,[])
    def test_plain_message_reply_and_restart_upload(self):
        row=self.tools.links(1,'demo',{'text':'hello','idempotencyKey':'hello'})
        self.assertEqual(row['status'],'NO_MATCH');self.assertEqual(self.scheduler.calls,[])
        body={'name':'video.webm','bytes':8,'idempotencyKey':'v'}
        row=self.tools.upload(1,'demo',body);self.tools.chunk(1,row['id'],0,b'\x1aE\xdf\xa3')
        self.tools.db.close();self.tools=AppDownloadTools(self.service,Path(self.tmp.name),self.storage)
        self.assertEqual(self.tools.upload(1,'demo',body)['bytesReceived'],4)
        self.tools.chunk(1,row['id'],4,b'data');self.tools.complete(1,row['id'])
        self.assertEqual(self.scheduler.calls[0]['task_name'],'download_storage_object')
    def test_untrusted_fields_cannot_select_storage_or_owner(self):
        data=b'%PDF-file'
        body={'name':'document.pdf','bytes':len(data),'idempotencyKey':'pdf','ownerId':2,'sourceStorageUri':'storage://managed/victim/private'}
        row=self.tools.upload(1,'demo',body);self.tools.chunk(1,row['id'],0,data);self.tools.complete(1,row['id'])
        call=self.scheduler.calls[0]['parameters'];self.assertEqual(call['ownerId'],1)
        self.assertNotIn('victim',call['sourceStorageUri'])

    def test_history_first_page_unions_only_recent_and_unfinished_entries(self):
        now = time.time()
        with patch('mytools_download_ingestion.app_tools.time.time', return_value=now-90000):
            expired = self.tools.upload(1, 'demo', {'name':'expired.png','bytes':8,'idempotencyKey':'expired'})
        with patch('mytools_download_ingestion.app_tools.time.time', return_value=now-100):
            uploading = self.tools.upload(1, 'demo', {'name':'active.png','bytes':8,'idempotencyKey':'upload'})
            old_active = self.tools.links(1, 'demo', {'text':'https://example.com/active','idempotencyKey':'old-active'})
            old_failed = self.tools.links(1, 'demo', {'text':'https://example.com/failed','idempotencyKey':'old-failed'})
            self.tools.links(2, 'other', {'text':'https://example.com/other','idempotencyKey':'other'})
        self.repository.update_status(UUID(old_active['requestId']), DownloadStatus.PLANNING)
        self.repository.update_status(UUID(old_failed['requestId']), DownloadStatus.FAILED)
        recent = [self.tools.links(1, 'demo', {'text':f'recent {i}','idempotencyKey':f'recent-{i}'})
                  for i in range(10)]
        recent_active = self.tools.links(1, 'demo', {'text':'https://example.com/new','idempotencyKey':'recent-active'})

        with patch.object(self.service, 'result_snapshot_for_owner', wraps=self.service.result_snapshot_for_owner) as read:
            page = self.tools.history_page(1, 10)
        by_id = {row['id']: row['status'] for row in page['items']}
        self.assertEqual(len(page['items']), 12)
        self.assertEqual(set(by_id), {row['id'] for row in recent[1:]} |
                         {recent_active['id'], old_active['id'], uploading['id']})
        self.assertEqual(by_id[old_active['id']], 'PLANNING')
        self.assertEqual(by_id[uploading['id']], 'UPLOADING')
        self.assertNotIn(expired['id'], by_id)
        self.assertNotIn(old_failed['id'], by_id)
        self.assertEqual({call.args[0] for call in read.call_args_list},
                         {UUID(recent_active['requestId']), UUID(old_active['requestId'])})
        older = self.tools.history_page(1, 10, page['nextCursor'])
        self.assertEqual(older['items'][0]['id'], recent[0]['id'])
        self.assertIsNone(older['nextCursor'])

    def test_history_cursor_handles_tied_timestamps_and_new_records(self):
        timestamp = time.time()
        with patch('mytools_download_ingestion.app_tools.time.time', return_value=timestamp):
            rows = [self.tools.links(1, 'demo', {'text':f'entry {i}','idempotencyKey':f'entry-{i}'})
                    for i in range(23)]
        first = self.tools.history_page(1, 10)
        self.tools.links(1, 'demo', {'text':'new entry','idempotencyKey':'new'})
        second = self.tools.history_page(1, 10, first['nextCursor'])
        third = self.tools.history_page(1, 10, second['nextCursor'])
        self.assertEqual([page['id'] for window in (first, second, third) for page in window['items']],
                         sorted((row['id'] for row in rows), reverse=True))
        self.assertIsNone(third['nextCursor'])
        with self.assertRaises(ValueError):
            self.tools.history_page(2, 10, first['nextCursor'])

class AppToolsHttpTest(unittest.TestCase):
    def test_authenticated_routes_stream_and_hide_other_owners(self):
        from http.server import ThreadingHTTPServer
        from threading import Thread
        from urllib.request import Request,urlopen
        from urllib.error import HTTPError
        import json
        from mytools_download_ingestion.http_api import create_handler
        with tempfile.TemporaryDirectory() as root:
            repo=InMemoryDownloadRequestRepository();scheduler=Scheduler();service=DownloadRequestService(repo,scheduler)
            tools=AppDownloadTools(service,Path(root),Storage())
            server=ThreadingHTTPServer(('127.0.0.1',0),create_handler(service,repo,'internal',app_tools=tools))
            thread=Thread(target=server.serve_forever,daemon=True);thread.start()
            def request(path,body=None,method='POST',owner='1',token='internal',offset=None):
                headers={'Authorization':'Bearer '+token,'X-Owner-Id':owner,'X-Resource-Username':'demo-user','Content-Type':'application/json'}
                if offset is not None: headers['X-Upload-Offset']=str(offset)
                data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
                with urlopen(Request(f'http://127.0.0.1:{server.server_port}/internal/v1/app-download-tools/'+path,data=data,headers=headers,method=method),timeout=5) as response:
                    return json.load(response)
            try:
                with self.assertRaises(HTTPError) as rejected: request('history',method='GET',token='wrong')
                self.assertEqual(rejected.exception.code,401)
                row=request('uploads',{'idempotencyKey':'image','name':'image.png','bytes':8})
                request('uploads/'+row['id']+'/content',b'\x89PNG\r\n\x1a\n','PUT',offset=0)
                with self.assertRaises(HTTPError) as rejected: request('uploads/'+row['id']+'/complete',{},owner='2')
                self.assertEqual(rejected.exception.code,404)
                done=request('uploads/'+row['id']+'/complete',{})
                self.assertEqual(done['status'],'RUNNING')
                self.assertEqual(scheduler.calls[0]['parameters']['resourceUsername'],'owner_1')
                self.assertEqual(request('history',method='GET',owner='2'),[])
                self.assertEqual(request('history',method='GET')[0]['id'],row['id'])
                self.assertEqual(request('history?limit=10',method='GET',owner='2'),
                                 {'items':[],'nextCursor':None})
                page = request('history?limit=10',method='GET')
                self.assertEqual([item['id'] for item in page['items']],[row['id']])
                self.assertIsNone(page['nextCursor'])
                for query in ('limit=0','limit=51','limit=10&limit=1','limit=10&before=x','before=x'):
                    with self.subTest(query=query), self.assertRaises(HTTPError) as rejected:
                        request('history?'+query,method='GET')
                    self.assertEqual(rejected.exception.code,400)
            finally:
                server.shutdown();server.server_close();thread.join();tools.db.close()
