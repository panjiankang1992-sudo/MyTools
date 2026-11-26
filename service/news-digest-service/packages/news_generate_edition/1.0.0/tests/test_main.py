"""离线验证新闻工作包的受控目标、租约传递和结果边界。"""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("news_generate", Path(__file__).parents[1] / "scripts/main.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class Response(io.BytesIO):
    status = 200


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.run_id, self.execution, self.lease = [str(uuid.uuid4()) for _ in range(3)]
        self.context = {"taskName": "news_generate_edition", "stepName": "generate", "executionId": self.execution, "parameters": {"runId": self.run_id}}
        self.result = {"runId": self.run_id, "editionId": str(uuid.uuid4()), "status": "READY"}

    def call(self, url="http://127.0.0.1:23350", token="x" * 32):
        return worker.execute(self.context, url, token, self.execution, self.lease)

    def test_sends_fixed_scope_and_live_lease_without_proxy(self):
        with patch.object(worker.urllib.request, "build_opener") as factory:
            factory.return_value.open.return_value = Response(json.dumps(self.result).encode())
            self.assertEqual(self.call(), self.result)
            request = factory.return_value.open.call_args.args[0]
            self.assertTrue(request.full_url.endswith("/" + self.run_id + "/generate"))
            self.assertEqual(request.get_header("X-task-lease-token"), self.lease)
            self.assertEqual(factory.call_args.args[0].proxies, {})
            self.assertEqual(json.loads(request.data), {})

    def test_rejects_unsafe_targets_before_network(self):
        with patch.object(worker.urllib.request, "build_opener") as factory:
            for url in ["http://example.com", "https://user:pass@example.com", "https://example.com/?other=1", "https://example.com/path", "file:///tmp/a"]:
                with self.assertRaises(ValueError): self.call(url)
            factory.assert_not_called()

    def test_rejects_missing_token_and_forged_context(self):
        with self.assertRaises(ValueError): self.call(token="")
        self.context["parameters"]["ownerId"] = 99
        with self.assertRaises(ValueError): self.call()
        del self.context["parameters"]["ownerId"]
        self.context["executionId"] = str(uuid.uuid4())
        with self.assertRaises(ValueError): self.call()

    def test_receipt_cannot_substitute_run_or_include_secrets(self):
        with patch.object(worker.urllib.request, "build_opener") as factory:
            for bad in [{**self.result, "runId": str(uuid.uuid4())}, {**self.result, "leaseToken": "secret"}, {**self.result, "status": "PENDING"}]:
                factory.return_value.open.return_value = Response(json.dumps(bad).encode())
                with self.assertRaises(ValueError): self.call()

    def test_response_size_is_bounded(self):
        with patch.object(worker.urllib.request, "build_opener") as factory:
            factory.return_value.open.return_value = Response(b" " * 16385)
            with self.assertRaises(ValueError): self.call()

    def test_redirect_is_never_followed(self):
        with self.assertRaises(ValueError): worker.NoRedirect().redirect_request(None, None, 302, None, None, "https://other")

    def test_result_is_atomic_and_contains_only_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            worker.write_result(self.result, path)
            self.assertEqual(json.loads(path.read_text()), self.result)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
