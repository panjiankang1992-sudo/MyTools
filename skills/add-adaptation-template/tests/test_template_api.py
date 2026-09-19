"""回环 HTTP 夹具验证脚本，不调用生产接口或模型。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('template_api', Path(__file__).parents[1] / 'scripts/template_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class TemplateApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.key = Path(self.tmp.name) / 'token'
        self.key.write_text('test-only-' + 'x' * 40)
        self.key.chmod(0o600)
        self.body = {'idempotencyKey': 'same-operation', 'code': 'cinematic', 'expectedLatestVersion': 0,
                     'name': 'Cinematic', 'description': 'Visual style', 'prompt': 'Rewrite with visual detail.'}

    def tearDown(self):
        self.tmp.cleanup()

    def test_origin_and_private_token(self):
        for value in ['http://example.com', 'http://localhost/admin', 'https://user:pass@example.com', 'https://example.com?a=1']:
            with self.assertRaises(ValueError):
                api.origin(value)
        self.key.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'PRIVATE_TOKEN_FILE_REQUIRED'):
            api.call('http://127.0.0.1:1', self.key)

    def test_request_validation_preserves_key_and_rejects_unpaired_metadata(self):
        path = Path(self.tmp.name) / 'request.json'
        path.write_text(json.dumps(self.body))
        self.assertEqual(api.request_file(path), self.body)
        for patch in [{'expectedLatestVersion': True}, {'expectedLatestVersion': 1.1}, {'prompt': ''}, {'code': '../admin'}, {'extra': 'bad'}]:
            path.write_text(json.dumps({**self.body, **patch}))
            with self.assertRaises(ValueError):
                api.request_file(path)
        path.write_text('{"code":"a","code":"b"}')
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_FIELD'):
            api.request_file(path)

    def test_list_publish_replay_and_redirect_never_resend(self):
        seen = []
        body = self.body
        summary = {'code': body['code'], 'version': 1, 'name': body['name'], 'description': body['description'],
                   'promptSha256': hashlib.sha256(body['prompt'].encode()).hexdigest()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                seen.append(('GET', self.path, self.headers.get('Authorization')))
                data = json.dumps({'items': [summary]}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                seen.append(('POST', data, self.headers.get('Authorization')))
                if data['idempotencyKey'] == 'redirect':
                    self.send_response(307)
                    self.send_header('Location', '/leak')
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(summary).encode())
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = 'http://127.0.0.1:' + str(server.server_port)
            self.assertEqual(api.call(url, self.key)['items'], [summary])
            self.assertEqual(api.call(url, self.key, body), api.call(url, self.key, body))
            self.assertEqual(seen[1][1], seen[2][1])
            self.assertTrue(all(row[2] == 'Bearer ' + self.key.read_text() for row in seen))
            with self.assertRaisesRegex(ValueError, 'HTTP_307'):
                api.call(url, self.key, {**body, 'idempotencyKey': 'redirect'})
            self.assertEqual(len(seen), 4)
        finally:
            server.shutdown()
            thread.join()
            server.server_close()


if __name__ == '__main__':
    unittest.main()
