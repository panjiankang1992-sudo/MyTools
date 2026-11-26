"""新版消息任务只透传投递标识，使用本地 HTTP 夹具验证租约与结果边界。"""
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

spec = importlib.util.spec_from_file_location('channel_worker', Path(__file__).parents[1] / 'packages/message_send_channel_message/1.0.0/scripts/main.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerTest(unittest.TestCase):
    """不向真实消息平台发送测试请求。"""

    def setUp(self):
        self.execution, self.lease, self.delivery = (str(uuid4()) for _ in range(3))
        self.context = {'taskName': 'message_send_channel_message', 'stepName': 'send_channel',
                        'executionId': self.execution, 'parameters': {'deliveryId': self.delivery, 'generation': 1}}
        self.response = {'id': self.delivery, 'generation': 1, 'status': 'PROVIDER_ACCEPTED', 'destinationId': str(uuid4())}
        self.status = 200
        self.seen = []
        parent = self

        class Handler(BaseHTTPRequestHandler):
            """仅记录本地伪造的执行身份。"""

            def do_POST(self):
                """返回受测试控制的小型 JSON 响应。"""
                parent.seen.append((self.path, dict(self.headers)))
                self.rfile.read(int(self.headers.get('Content-Length', 0)))
                self.send_response(parent.status)
                self.send_header('Location', '/forbidden')
                self.end_headers()
                self.wfile.write(json.dumps(parent.response).encode())

            def log_message(self, *args):
                """关闭 HTTP 夹具日志以避免输出伪造凭据。"""

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def execute(self):
        return worker.execute(self.context, self.url, 'test-worker-token-' * 3, self.execution, self.lease)

    def test_forwards_lease_and_projects_only_terminal_delivery_status(self):
        self.assertEqual(self.execute(), {'deliveryId': self.delivery, 'status': 'PROVIDER_ACCEPTED'})
        path, headers = self.seen[0]
        self.assertIn('generation=1', path)
        self.assertEqual(headers['X-Task-Lease-Token'], self.lease)
        self.assertEqual(headers['X-Task-Execution-Id'], self.execution)

    def test_unknown_is_a_delivery_result_not_a_request_to_resend(self):
        self.response['status'] = 'UNKNOWN'
        self.assertEqual(self.execute()['status'], 'UNKNOWN')
        self.assertEqual(len(self.seen), 1)

    def test_rejects_context_injection_and_non_integer_generation(self):
        self.context['parameters']['recipient'] = 'forbidden@example.com'
        with self.assertRaises(ValueError):
            self.execute()
        self.context['parameters'].pop('recipient')
        for invalid in [True, 0, 101, 1.1, '1']:
            self.context['parameters']['generation'] = invalid
            with self.assertRaises(ValueError):
                self.execute()
        self.assertFalse(self.seen)

    def test_rejects_foreign_receipt_and_unfinished_status(self):
        for field, value in [('id', str(uuid4())), ('generation', 2), ('status', 'SENDING')]:
            previous = self.response[field]
            self.response[field] = value
            with self.assertRaises(ValueError):
                self.execute()
            self.response[field] = previous

    def test_never_follows_redirect(self):
        self.status = 302
        with self.assertRaises(ValueError):
            self.execute()
        self.assertEqual(len(self.seen), 1)

    def test_rejects_unsafe_url_and_short_token_before_network(self):
        for url in ['http://example.com', 'https://user:pass@example.com', 'https://example.com/path', self.url + '?x=1']:
            with self.assertRaises(ValueError):
                worker.execute(self.context, url, 'x' * 32, self.execution, self.lease)
        with self.assertRaises(ValueError):
            worker.execute(self.context, self.url, '', self.execution, self.lease)
        self.assertFalse(self.seen)


if __name__ == '__main__':
    unittest.main()
