"""验证受限包只使用单次 UDS 协议，不输出宿主结果或敏感诊断。"""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest

MODULE = Path(__file__).resolve().parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("adaptation_broker_task", MODULE)
TASK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TASK)


class BrokerPackageTest(unittest.TestCase):
    def exchange(self, reply, handle="a" * 43):
        with tempfile.TemporaryDirectory(prefix="np-", dir="/tmp") as directory:
            root = Path(directory)
            (root / "handle").write_text(handle)
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
                server.bind(str(root / "b.sock"))
                server.listen(1)
                server.settimeout(2)
                received = []

                def serve():
                    with server.accept()[0] as client:
                        received.append(client.recv(2048))
                        client.sendall(reply)

                thread = threading.Thread(target=serve)
                thread.start()
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    result = TASK.invoke(directory)
                thread.join(3)
                self.assertFalse(thread.is_alive())
                self.assertEqual("", stdout.getvalue())
                self.assertEqual("", stderr.getvalue())
                return result, received

    def test_single_request_has_only_operation_and_handle(self):
        code, requests = self.exchange(b'{"status":"SUCCEEDED","errorCode":null}\n')
        self.assertEqual(0, code)
        self.assertEqual([{"op": "run", "handle": "a" * 43}], [json.loads(value) for value in requests])

    def test_failure_cancel_and_timeout_never_become_success(self):
        for status in ("FAILED", "CANCELLED", "TIMED_OUT"):
            with self.subTest(status=status):
                self.assertEqual(1, self.exchange(json.dumps({"status": status, "errorCode": "READER_044"}).encode() + b"\n")[0])

    def test_duplicates_extra_fields_and_unknown_status_are_rejected(self):
        for reply in (b'{"status":"FAILED","status":"SUCCEEDED","errorCode":null}\n',
                      b'{"status":"SUCCEEDED","errorCode":null,"body":"private"}\n',
                      b'{"status":"UNKNOWN","errorCode":null}\n'):
            with self.subTest(reply=reply):
                self.assertEqual(1, self.exchange(reply)[0])

    def test_size_and_trailing_messages_are_rejected(self):
        self.assertEqual(1, self.exchange(b"x" * 1025)[0])
        self.assertEqual(1, self.exchange(b'{"status":"SUCCEEDED","errorCode":null}\n{}\n')[0])

    def test_invalid_handle_and_missing_socket_fail_without_diagnostics(self):
        with tempfile.TemporaryDirectory(prefix="np-", dir="/tmp") as directory:
            (Path(directory) / "handle").write_text("invalid")
            self.assertEqual(1, TASK.invoke(directory))
            (Path(directory) / "handle").write_text("a" * 43)
            self.assertEqual(1, TASK.invoke(directory))


if __name__ == "__main__":
    unittest.main()
