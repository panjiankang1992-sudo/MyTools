#!/usr/bin/env python3
"""本地集成验收：真实 PDF 服务与 Worker，替身 Scheduler/Storage/Asset，不连接生产。"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from uuid import uuid4


def main():
    """验证上传、检查、计划、发布、下载、账户隔离和幂等。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--classpath', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    service = Path(__file__).resolve().parents[1]
    tasks, uploads, objects = {}, {}, {}
    with tempfile.TemporaryDirectory(prefix='pdf-integration-') as folder:
        root = Path(folder)
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, body, status=200):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                if self.headers.get('Transfer-Encoding') == 'chunked':
                    raw = bytearray()
                    while True:
                        size = int(self.rfile.readline().split(b';')[0], 16)
                        if not size:
                            self.rfile.readline()
                            break
                        raw.extend(self.rfile.read(size))
                        self.rfile.read(2)
                    body = json.loads(raw)
                else:
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.path == '/api/v1/task-instances':
                    work = body['parameters']['workId']
                    if work not in tasks:
                        tasks[work] = {'id': str(uuid4()), 'status': 'RUNNING'}
                    self.reply(tasks[work])
                elif self.path == '/api/internal/v1/storage/uploads':
                    key = body['idempotencyKey']
                    row = next((row for row in uploads.values() if row['idempotencyKey'] == key), None)
                    if row is None:
                        row = {**body, 'id': str(uuid4()), 'status': 'UPLOADING'}
                        uploads[row['id']] = row
                    self.reply(row)
                elif self.path == '/internal/v1/assets':
                    self.reply({'id': str(uuid4()), 'version': 1})
                else:
                    self.reply({}, 404)

            def do_PUT(self):
                row = uploads[self.path.split('/')[-2]]
                data = self.rfile.read(int(self.headers['Content-Length']))
                assert len(data) == row['expectedSize']
                assert hashlib.sha256(data).hexdigest() == row['expectedSha256']
                objects[row['relativePath']] = data
                row.update(status='SUCCEEDED', storageUri=f'storage://{row["rootName"]}/{row["relativePath"]}')
                self.reply(row)

            def do_GET(self):
                if self.path.startswith('/api/v1/task-instances/'):
                    self.reply(next(row for row in tasks.values() if row['id'] == self.path.split('/')[-1]))
                elif self.path.startswith('/api/internal/v1/storage/uploads/'):
                    self.reply(uploads[self.path.split('/')[-1]])
                elif self.path.startswith('/api/internal/v1/storage/objects/content?'):
                    query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                    data = objects[query['path'][0]]
                    self.send_response(200)
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                else:
                    self.reply({}, 404)
        mock = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=mock.serve_forever, daemon=True)
        thread.start()
        mock_url = f'http://127.0.0.1:{mock.server_port}'
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        url = f'http://127.0.0.1:{port}'
        cp = str(service / 'target/classes') + os.pathsep + args.classpath.read_text().strip()
        command = ['java', '-cp', cp, 'com.yuyutian.mytools.pdf.PdfApplication',
                   '--server.port=' + str(port), '--spring.datasource.url=jdbc:h2:mem:acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE',
                   '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.datasource.driver-class-name=org.h2.Driver',
                   '--pdf.root=' + str(root / 'inputs'), '--pdf.enabled=true', '--pdf.text-enabled=true', '--pdf.token=acceptance-api', '--pdf.worker-token=acceptance-worker',
                   '--pdf.scheduler-url=' + mock_url, '--pdf.scheduler-token=acceptance-scheduler', '--pdf.storage-url=' + mock_url, '--pdf.storage-token=acceptance-storage', '--pdf.reconcile-ms=100']
        with (root / 'service.log').open('w') as log:
            process = subprocess.Popen(command, stdout=log, stderr=log)
            try:
                def call(method, path, body=None, owner=7, key=None, offset=None, raw=False):
                    headers = {'Authorization': 'Bearer acceptance-api', 'X-Owner-Id': str(owner)}
                    if key:
                        headers['Idempotency-Key'] = key
                    if offset is not None:
                        headers['X-Upload-Offset'] = str(offset)
                    if isinstance(body, bytes):
                        data = body
                        headers['Content-Type'] = 'application/octet-stream'
                    else:
                        data = json.dumps(body).encode() if body is not None else None
                        headers['Content-Type'] = 'application/json'
                    request = urllib.request.Request(url + '/internal/v1/pdf' + path, data, headers, method=method)
                    with urllib.request.urlopen(request, timeout=30) as response:
                        return response.read() if raw else json.load(response)
                for _ in range(100):
                    try:
                        call('GET', '/capabilities')
                        break
                    except (urllib.error.URLError, ConnectionError):
                        if process.poll() is not None:
                            raise RuntimeError('PDF service failed to start')
                        time.sleep(.1)
                def worker(work):
                    for _ in range(100):
                        if work in tasks:
                            break
                        time.sleep(.1)
                    task = tasks[work]
                    directory = root / work
                    directory.mkdir()
                    context = directory / 'context.json'
                    context.write_text(json.dumps({'taskInstanceId': task['id'], 'fencingToken': 1, 'stepName': 'process', 'parameters': {'workId': work}}))
                    lease = directory / 'lease'
                    lease.write_text('acceptance-lease')
                    env = {**os.environ, 'PYTHONPATH': str(service.parent / 'task-executor-service/sdk/python'),
                           'TASK_CONTEXT_FILE': str(context), 'TASK_LEASE_TOKEN_FILE': str(lease), 'TASK_API_URL': mock_url,
                           'TASK_EXECUTION_ID': str(uuid4()), 'TASK_RESULT_FILE': str(directory / 'result.json'),
                           'PDF_SERVICE_URL': url, 'PDF_WORKER_TOKEN': 'acceptance-worker', 'PDF_ALLOW_UNSANDBOXED_TEST': 'true',
                           'STORAGE_GATEWAY_URL': mock_url, 'STORAGE_INTERNAL_TOKEN': 'acceptance-storage',
                           'ASSET_REGISTRY_URL': mock_url, 'ASSET_REGISTRY_INTERNAL_TOKEN': 'acceptance-assets'}
                    subprocess.run([sys.executable, str(args.package.resolve() / 'scripts/main.py')], cwd=directory, env=env, check=True, timeout=300)
                    task['status'] = 'SUCCEEDED'
                data = args.input.read_bytes()
                body = {'name': 'acceptance.pdf', 'bytes': len(data), 'mediaType': 'application/pdf'}
                upload = call('POST', '/uploads', body, key='acceptance-upload')
                assert call('POST', '/uploads', body, key='acceptance-upload')['id'] == upload['id']
                for offset in range(0, len(data), 1048576):
                    call('PUT', f'/uploads/{upload["id"]}/content', data[offset:offset+1048576], offset=offset)
                call('POST', f'/uploads/{upload["id"]}/complete', {})
                worker(upload['id'])
                inspected = call('GET', '/inputs/' + upload['id'])
                assert inspected['status'] == 'READY'
                plan_body = {'operation': 'PDF_PAGE_CUT', 'inputIds': [upload['id']], 'options': {'axis': 'VERTICAL', 'position': .5}}
                plan = call('POST', '/plans', plan_body, key='acceptance-plan')
                worker(plan['id'])
                ready = call('GET', '/plans/' + plan['id'])
                assert ready['status'] == 'READY', ready
                assert ready['result']['pageCount'] == inspected['result']['pageCount'] * 2
                assert call('GET', f'/plans/{plan["id"]}/pages/0/thumbnail', raw=True).startswith(b'\x89PNG')
                job = call('POST', '/jobs', {'planId': plan['id']}, key='acceptance-job')
                assert call('POST', '/jobs', {'planId': plan['id']}, key='acceptance-job')['id'] == job['id']
                try:
                    call('GET', '/jobs/' + job['id'], owner=8)
                    raise AssertionError('Cross-owner access was accepted')
                except urllib.error.HTTPError as error:
                    assert error.code == 404
                output = call('GET', f'/jobs/{job["id"]}/artifacts/0/content', raw=True)
                assert hashlib.sha256(output).hexdigest() == job['result']['artifacts'][0]['sha256']
                def run_plan(operation, input_ids=None, options=None, content=None, attachments=None):
                    body = {'operation': operation, 'options': options or {}}
                    if input_ids is not None:
                        body['inputIds'] = input_ids
                    if content is not None:
                        body['content'] = content
                    if attachments is not None:
                        body['attachmentIds'] = attachments
                    key = str(uuid4())
                    plan = call('POST', '/plans', body, key=key)
                    worker(plan['id'])
                    ready = call('GET', '/plans/' + plan['id'])
                    assert ready['status'] == 'READY', ready
                    return call('POST', '/jobs', {'planId': plan['id']}, key=key)
                def add_input(data, name, mime):
                    row = call('POST', '/uploads', {'name': name, 'mediaType': mime, 'bytes': len(data)}, key=str(uuid4()))
                    for offset in range(0, len(data), 1048576):
                        call('PUT', f'/uploads/{row["id"]}/content', data[offset:offset+1048576], offset=offset)
                    call('POST', f'/uploads/{row["id"]}/complete', {})
                    worker(row['id'])
                    return row['id']
                completed_operations = ['PDF_PAGE_CUT']
                for operation, ids, options in [('PDF_SPLIT', [upload['id']], {'pagesPerFile': 1}),
                                                ('PDF_MERGE', [upload['id'], upload['id']], {})]:
                    result = run_plan(operation, ids, options)
                    assert result['status'] == 'SUCCEEDED'
                    completed_operations.append(operation)
                image_job = run_plan('PDF_TO_IMAGES', [upload['id']], {'dpi': 72, 'format': 'PNG'})
                completed_operations.append('PDF_TO_IMAGES')
                image_data = call('GET', f'/jobs/{image_job["id"]}/artifacts/0/content', raw=True)
                image_id = add_input(image_data, 'page.png', 'image/png')
                image_pdf = run_plan('IMAGES_TO_PDF', [image_id], {'paperSize': 'A4', 'fit': 'FIT'})
                assert image_pdf['result']['pageCount'] == 1
                completed_operations.append('IMAGES_TO_PDF')
                if os.environ.get('PDF_FONT_FILE'):
                    text = run_plan('TEXT_TO_PDF', content={'format': 'TEXT', 'text': 'Chinese PDF test: \u4e2d\u6587\u6587\u5b57\u3002'})
                    text_pdf = call('GET', f'/jobs/{text["id"]}/artifacts/0/content', raw=True)
                    text_id = add_input(text_pdf, 'text.pdf', 'application/pdf')
                    extracted = run_plan('PDF_TO_TEXT', [text_id])
                    assert '\u4e2d\u6587\u6587\u5b57' in extracted['result']['textPreview']
                    markdown = run_plan('MARKDOWN_TO_PDF', content={'format': 'MARKDOWN', 'text': '# Report\n\n[Link](https://example.com)\n\n![page](attachment:' + image_id + ')'}, attachments=[image_id])
                    assert markdown['result']['pageCount'] >= 1
                    completed_operations += ['TEXT_TO_PDF', 'PDF_TO_TEXT', 'MARKDOWN_TO_PDF']
                args.output.mkdir(parents=True, exist_ok=True)
                (args.output / 'result.pdf').write_bytes(output)
                summary = {'passed': True, 'operations': completed_operations, 'inputPages': inspected['result']['pageCount'], 'outputPages': ready['result']['pageCount'], 'sha256': hashlib.sha256(output).hexdigest(), 'checks': ['chunk upload', 'real worker inspection', 'frozen preview', 'publication', 'download integrity', 'cross-owner rejection', 'idempotency']}
                (args.output / 'acceptance.json').write_text(json.dumps(summary, indent=2) + '\n')
                print(json.dumps(summary))
            finally:
                process.terminate()
                process.wait(timeout=15)
                mock.shutdown()


if __name__ == '__main__':
    main()
