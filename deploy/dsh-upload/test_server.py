"""在隔离 UNIX 套接字上验证分片语义和一次性转发。"""

from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from uuid import uuid4

import server


GRANT = "A" * 43
UPSTREAM_COOKIE = f'dsh-auth-{"C" * 43}=v1.payload.{"D" * 43}'


class UnixConnection(HTTPConnection):
    def __init__(self, address):
        super().__init__("fixture", timeout=3)
        self.address = address

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.address)


class UploadTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="dsh-relay-", dir="/tmp")
        self.root = Path(self.folder.name)
        self.calls = []
        self.started = threading.Event()
        self.release = threading.Event()
        self.time = 1000
        self.available = True
        self.start_fixture()

    def tearDown(self):
        if self.available:
            self.stop_fixture()
        self.folder.cleanup()

    def relay(self, target, parts, size, cookie, query):
        self.calls.append((target, b"".join(part.read_bytes() for part in parts), size, cookie, query))
        self.started.set()
        self.release.wait(3)
        return server.Reply(200, b'{"ok":true}', state="complete")

    def start_fixture(self, relay=None):
        self.store = server.Store(self.root / "state", relay=relay or self.relay, clock=lambda: self.time)
        self.service = server.LocalServer(self.store, str(self.root / "relay.sock"))
        self.thread = threading.Thread(target=self.service.serve_forever, daemon=True)
        self.thread.start()
        self.available = True

    def stop_fixture(self):
        self.release.set()
        self.service.shutdown()
        self.thread.join(3)
        self.service.server_close()
        self.store.close()
        (self.root / "relay.sock").unlink()
        self.available = False

    def request(self, method, route, body=None, grant=GRANT, **fields):
        transport = UnixConnection(str(self.root / "relay.sock"))
        headers = {"X-MyTools-Dsh-Upstream": UPSTREAM_COOKIE, **fields}
        if grant is not None:
            headers["Cookie"] = f"__Host-mytools-dsh={grant}"
        try:
            transport.request(method, "/_mytools/upload/" + route, body=body, headers=headers)
            response = transport.getresponse()
            return response.status, response.headers, response.read()
        finally:
            transport.close()

    def part(self, upload_id, size, idx, body, grant=GRANT):
        return self.request("PUT", "part", body, grant, **{
            "X-Dsh-Upload-Id": upload_id,
            "X-Dsh-Upload-Size": str(size),
            "X-Dsh-Upload-Index": str(idx),
        })[0]

    def commit(self, upload_id, size, target="prompt", grant=GRANT, query=None):
        headers = {
            "X-Dsh-Upload-Id": upload_id,
            "X-Dsh-Upload-Size": str(size),
            "X-Dsh-Upload-Target": target,
        }
        if query is not None:
            headers["X-Dsh-Upload-Query"] = query
        return self.request("POST", "commit", b"", grant, **headers)

    def test_authentication_and_path_are_exact(self):
        code, headers, _ = self.request("GET", "status")
        self.assertEqual(204, code)
        self.assertEqual("1", headers["X-MyTools-Dsh-Upload"])
        self.assertEqual(401, self.request("GET", "status", grant=None)[0])
        self.assertEqual(401, self.request("GET", "status", **{"X-MyTools-Dsh-Upstream": "unverified"})[0])
        self.assertEqual(404, self.request("GET", "status?query=1")[0])
        self.assertEqual(405, self.request("POST", "status")[0])
        self.assertEqual(400, self.part("../invalid", 1, 0, b""))
        self.assertEqual(413, self.part(str(uuid4()), server.MAX_SIZE + 1, 0, b"a"))

    def test_parts_are_immutable_and_commit_replays_original_receipt(self):
        upload = str(uuid4())
        start = b"A" * server.PART_SIZE
        end = b"end"
        size = len(start) + len(end)

        self.assertEqual(204, self.part(upload, size, 1, end))
        self.assertEqual(204, self.part(upload, size, 1, end))
        self.assertEqual(409, self.part(upload, size, 1, b"bad"))
        self.assertEqual(409, self.part(upload, size + 1, 0, start))
        self.assertEqual(403, self.part(upload, size, 1, end, "B" * 43))
        self.assertEqual(400, self.part(upload, size, 0, b"short"))
        self.assertEqual(409, self.commit(upload, size)[0])
        self.assertEqual(204, self.part(upload, size, 0, start))
        self.assertEqual(403, self.commit(upload, size, grant="B" * 43)[0])
        self.assertEqual(400, self.commit(upload, size, target="/api/invalid")[0])
        self.assertEqual(202, self.commit(upload, size)[0])
        self.assertTrue(self.started.wait(3))
        status, headers, _ = self.commit(upload, size)
        self.assertEqual((202, "pending"), (status, headers["X-Dsh-Upload-State"]))
        self.assertEqual(409, self.commit(upload, size, target="file-json")[0])
        self.assertEqual([("prompt", start + end, size, UPSTREAM_COOKIE, "")], self.calls)
        self.release.set()
        for _ in range(30):
            status, headers, body = self.commit(upload, size)
            if status != 202:
                break
            time.sleep(0.01)
        self.assertEqual((200, "complete", {"ok": True}),
                         (status, headers["X-Dsh-Upload-State"], json.loads(body)))
        self.assertEqual(200, self.commit(upload, size)[0])
        self.assertEqual(1, len(self.calls))

    def test_interrupted_part_can_resume_without_changing_completed_parts(self):
        upload = str(uuid4())
        first = b"A" * server.PART_SIZE
        last = b"last"
        size = len(first) + len(last)
        self.assertEqual(204, self.part(upload, size, 0, first))
        transport = UnixConnection(str(self.root / "relay.sock"))
        try:
            transport.request("PUT", "/_mytools/upload/part", body=last[:1], headers={
                "X-MyTools-Dsh-Upstream": UPSTREAM_COOKIE,
                "Cookie": f"__Host-mytools-dsh={GRANT}",
                "X-Dsh-Upload-Id": upload,
                "X-Dsh-Upload-Size": str(size),
                "X-Dsh-Upload-Index": "1",
                "Content-Length": str(len(last)),
            })
            transport.sock.shutdown(socket.SHUT_WR)
            response = transport.getresponse()
            self.assertEqual(400, response.status)
            self.assertEqual({"error": "upload_incomplete_part"}, json.loads(response.read()))
        finally:
            transport.close()
        self.assertEqual(409, self.commit(upload, size)[0])
        self.assertEqual(204, self.part(upload, size, 1, last))
        self.assertEqual(202, self.commit(upload, size)[0])
        self.assertTrue(self.started.wait(3))
        self.assertEqual([("prompt", first + last, size, UPSTREAM_COOKIE, "")], self.calls)
        self.stop_fixture()
        self.start_fixture()
        self.assertEqual(200, self.commit(upload, size)[0])
        self.assertEqual(1, len(self.calls))

    def test_ambiguous_dispatch_is_never_retried_after_restart(self):
        self.stop_fixture()
        attempts = []
        def relay(*_args):
            attempts.append(1)
            raise ConnectionResetError
        self.start_fixture(relay)
        upload = str(uuid4())
        self.assertEqual(204, self.part(upload, 2, 0, b"{}"))
        self.assertEqual(202, self.commit(upload, 2)[0])
        for _ in range(30):
            status, headers, _ = self.commit(upload, 2)
            if status != 202:
                break
            time.sleep(0.01)
        self.assertEqual((409, "unknown"), (status, headers["X-Dsh-Upload-State"]))
        self.stop_fixture()
        self.start_fixture(relay)
        status, headers, _ = self.commit(upload, 2)
        self.assertEqual((409, "unknown"), (status, headers["X-Dsh-Upload-State"]))
        self.assertEqual([1], attempts)

    def test_binary_metadata_is_strict_and_bound_to_dispatch(self):
        upload = str(uuid4())
        size = 3
        self.assertEqual(204, self.part(upload, size, 0, b"\x00\x01\xff"))
        for malformed in ("", "sessionId=", "sessionId=x&sessionId=y", "sessionId=x&token=y",
                          "sessionId=%zz", "sessionId=%FF", "sessionId=x&name=Some%0AName",
                          "sessionId=" + "x" * 257, "sessionId=x&name=" + "x" * 1025,
                          "sessionId=x&mediaType=image%2Fpng%3Bunsafe", "sessionId=x&mediaType="):
            with self.subTest(query=malformed[:32]):
                self.assertEqual(400, self.commit(upload, size, "file-binary", query=malformed)[0])
        self.assertEqual(400, self.commit(upload, size, "file-binary")[0])
        self.assertEqual(400, self.commit(upload, size, "prompt", query="sessionId=x")[0])

        actual = "name=%E5%9B%BE+%281%29.png&sessionId=case%3Asynthetic&mediaType=image%2Fpng"
        normalized = "sessionId=case%3Asynthetic&name=%E5%9B%BE+%281%29.png&mediaType=image%2Fpng"
        self.assertEqual(202, self.commit(upload, size, "file-binary", query=actual)[0])
        self.assertTrue(self.started.wait(3))
        self.assertEqual(202, self.commit(upload, size, "file-binary", query=normalized)[0])
        self.assertEqual(409, self.commit(upload, size, "file-binary", query="sessionId=another")[0])
        self.assertEqual([("file-binary", b"\x00\x01\xff", size, UPSTREAM_COOKIE, normalized)], self.calls)
        metadata = (self.root / "state" / upload / "metadata.json").read_text()
        self.assertNotIn("synthetic", metadata)
        self.assertNotIn("png", metadata)
        self.release.set()
        for _ in range(30):
            status = self.commit(upload, size, "file-binary", query=actual)[0]
            if status != 202:
                break
            time.sleep(0.01)
        self.assertEqual(200, status)
        self.stop_fixture()
        self.start_fixture()
        self.assertEqual(200, self.commit(upload, size, "file-binary", query=normalized)[0])
        self.assertEqual(409, self.commit(upload, size, "file-binary", query="sessionId=another")[0])
        self.assertEqual(1, len(self.calls))

    def test_abandoned_reservations_expire_and_capacity_is_enforced(self):
        first = str(uuid4())
        self.assertEqual(204, self.part(first, 1, 0, b"a"))
        self.assertEqual(204, self.part(str(uuid4()), 1, 0, b"b"))
        last = str(uuid4())
        self.assertEqual(503, self.part(last, 1, 0, b"c"))
        self.time += server.STAGE_TTL + 1
        self.assertEqual(204, self.part(last, 1, 0, b"c"))
        self.assertEqual(404, self.commit(first, 1)[0])

    def test_total_record_quota_is_bound_to_browser_grant(self):
        self.release.set()
        with patch.object(server, "MAX_OWNER_RECORDS", 2):
            finished = []
            for _ in range(2):
                unique = str(uuid4())
                self.assertEqual(204, self.part(unique, 2, 0, b"{}"))
                self.assertEqual(202, self.commit(unique, 2)[0])
                for _ in range(30):
                    status = self.commit(unique, 2)[0]
                    if status != 202:
                        break
                    time.sleep(0.01)
                self.assertEqual(200, status)
                finished.append(unique)
            self.assertEqual(503, self.part(str(uuid4()), 2, 0, b"{}"))
            self.assertEqual(204, self.part(str(uuid4()), 2, 0, b"{}", "B" * 43))
            self.assertEqual(200, self.commit(finished[0], 2)[0])

    def test_no_handler_thread_is_started_over_the_concurrency_limit(self):
        self.stop_fixture()
        with patch.object(server, "MAX_HANDLERS", 1):
            self.start_fixture()
        entered = threading.Event()
        leave = threading.Event()
        handle_get = server.Handler.do_GET

        def delayed_get(handler):
            entered.set()
            leave.wait(2)
            handle_get(handler)

        replies = []
        with patch.object(server.Handler, "do_GET", delayed_get):
            worker = threading.Thread(target=lambda: replies.append(self.request("GET", "status")[0]))
            worker.start()
            try:
                self.assertTrue(entered.wait(2))
                status, headers, _ = self.request("GET", "status")
                self.assertEqual((503, "1"), (status, headers["Retry-After"]))
            finally:
                leave.set()
                worker.join(2)
        self.assertEqual([204], replies)
        for _ in range(10):
            status = self.request("GET", "status")[0]
            if status != 503:
                break
            time.sleep(0.01)
        self.assertEqual(204, status)


class ForwardTest(unittest.TestCase):
    def test_forward_uses_fixed_origin_and_streams_exact_bytes(self):
        request = []
        class Upstream(BaseHTTPRequestHandler):
            def do_POST(self):
                original = self.rfile.read(int(self.headers["Content-Length"]))
                request.append((self.path, self.headers["Origin"], self.headers["Host"], self.headers["Content-Type"],
                                self.headers["Cookie"], self.headers["Transfer-Encoding"], original))
                self.send_response(202)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.end_headers()
                self.wfile.write(b'{"queued":true}')

            def log_message(self, _format, *_args):
                pass

        with tempfile.TemporaryDirectory() as directory, ThreadingHTTPServer(("127.0.0.1", 0), Upstream) as upstream:
            worker = threading.Thread(target=upstream.serve_forever, daemon=True)
            worker.start()
            first = Path(directory) / "first"
            second = Path(directory) / "second"
            first.write_bytes(b'{"data":"')
            second.write_bytes(b'value"}')
            address, port = upstream.server_address
            with patch.object(server, "HTTPConnection", return_value=HTTPConnection(address, port)):
                reply = server.forward("file-json", (first, second), 16, UPSTREAM_COOKIE)
                binary = server.forward("file-binary", (first, second), 16, UPSTREAM_COOKIE, "sessionId=safe")
            upstream.shutdown()
            worker.join(3)
        self.assertEqual(server.Reply(202, b'{"queued":true}', "application/json", "complete"), reply)
        self.assertEqual(reply, binary)
        self.assertEqual([
            ("/api/fileUploads/upload", "https://dsh-app.yuyutian.top", "dsh-app.yuyutian.top",
             "application/json", UPSTREAM_COOKIE, None, b'{"data":"value"}'),
            ("/api/session/uploadFileBinary?sessionId=safe", "https://dsh-app.yuyutian.top", "dsh-app.yuyutian.top",
             "application/octet-stream", UPSTREAM_COOKIE, None, b'{"data":"value"}'),
        ], request)

    def test_truncated_upstream_response_does_not_create_a_receipt(self):
        class Truncated(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "99")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def log_message(self, _format, *_args):
                pass

        with tempfile.TemporaryDirectory() as directory, ThreadingHTTPServer(("127.0.0.1", 0), Truncated) as upstream:
            worker = threading.Thread(target=upstream.serve_forever, daemon=True)
            worker.start()
            try:
                part = Path(directory) / "part"
                part.write_bytes(b"{}")
                address, port = upstream.server_address
                with patch.object(server, "HTTPConnection", return_value=HTTPConnection(address, port)):
                    with self.assertRaisesRegex(OSError, "Incomplete upstream response"):
                        server.forward("prompt", (part,), 2, UPSTREAM_COOKIE)
            finally:
                upstream.shutdown()
                worker.join(3)


if __name__ == "__main__":
    unittest.main()
