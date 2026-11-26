"""验证本机运维入口和显式清理操作的边界。"""
import contextlib
import io
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
import news_operations


class NewsOperationsTest(unittest.TestCase):
    def setUp(self):
        self.calls, self.reply, self.code = [], {"counts": {"delivery_unknown": 1}}, 200
        test = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                test.calls.append((self.command, self.path, self.headers.get("Authorization"), b""))
                self.respond()
            def do_POST(self):
                test.calls.append((self.command, self.path, self.headers.get("Authorization"), self.rfile.read(int(self.headers.get("Content-Length", "0")))))
                self.respond()
            do_PUT = do_POST
            def respond(self):
                self.send_response(test.code)
                if test.code == 302:
                    self.send_header("Location", "http://example.test/credential-sink")
                self.end_headers()
                self.wfile.write(json.dumps(test.reply).encode())
            def log_message(self, *_args):
                pass
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
    def invoke(self, *args):
        out, error = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, {"NEWS_ADMIN_TOKEN": "test-only-admin-" * 3}), contextlib.redirect_stdout(out), contextlib.redirect_stderr(error):
            code = news_operations.main(["--url", self.url, *args])
        return code, out.getvalue(), error.getvalue()
    def test_default_is_read_only_aggregate_status(self):
        code, out, error = self.invoke()
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["counts"]["delivery_unknown"], 1)
        self.assertEqual(self.calls[0][0:2], ("GET", "/internal/admin/operations"))
        self.assertNotIn("test-only-admin", out + error)
    def test_sweep_needs_execute_and_never_sends_messages(self):
        code, out, _ = self.invoke("--action", "sweep")
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(out)["executeRequired"])
        self.assertEqual(self.calls, [])
        self.reply = {"deliveryBodies": 1}
        self.assertEqual(self.invoke("--action", "sweep", "--execute")[0], 0)
        self.assertEqual(self.calls[0][0:2], ("POST", "/internal/admin/retention/sweep"))
    def test_nonlocal_urls_and_redirects_never_receive_credentials(self):
        for url in ("http://example.test", "http://127.0.0.1@evil.test", "http://127.0.0.1?redirect=evil", "https://127.0.0.1"):
            self.assertEqual(self.invoke("--url", url)[0], 1)
        self.assertEqual(self.calls, [])
        self.code = 302
        self.assertEqual(self.invoke()[0], 1)
        self.assertEqual(len(self.calls), 1)
    def test_error_responses_and_unexpected_fields_are_redacted(self):
        self.code, self.reply = 500, {"private": "secret-provider-credential"}
        code, out, error = self.invoke()
        self.assertEqual(code, 1)
        self.assertNotIn("secret-provider", out + error)
        self.code = 200
        code, out, error = self.invoke()
        self.assertEqual(code, 1)
        self.assertNotIn("secret-provider", out + error)
    def test_nested_unexpected_content_is_not_printed(self):
        self.reply = {"counts": {"private": "nested-secret"}}
        code, out, error = self.invoke()
        self.assertEqual(code, 1)
        self.assertNotIn("nested-secret", out + error)

    def test_source_policy_validates_and_sends_only_hours(self):
        self.assertEqual(self.invoke("--action", "source-policy", "--source-id", "../bad", "--evidence-hours", "1", "--execute")[0], 1)
        self.assertEqual(self.calls, [])
        self.reply = {"evidenceHours": 24}
        self.assertEqual(self.invoke("--action", "source-policy", "--source-id", "verified-source", "--evidence-hours", "24", "--execute")[0], 0)
        self.assertEqual(json.loads(self.calls[0][3]), {"evidenceHours": 24})


if __name__ == "__main__":
    unittest.main()
