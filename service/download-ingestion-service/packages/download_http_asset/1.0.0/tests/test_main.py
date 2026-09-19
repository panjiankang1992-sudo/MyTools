"""Tests for the bounded HTTP asset download task."""

import importlib.util
from contextlib import redirect_stderr
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import socket
from unittest.mock import patch
from urllib.error import HTTPError

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_http_asset", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    """Provide a context-managed streaming HTTP response."""

    def __init__(self, content):
        self.stream = io.BytesIO(content)
        self.headers = {"Content-Length": str(len(content))}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.stream.close()

    def read(self, size):
        """Read one response chunk."""
        return self.stream.read(size)


class DownloadHttpAssetTest(unittest.TestCase):
    """Validate safe and atomic download behavior."""

    def test_downloads_and_publishes_verified_file(self):
        """A valid stream is hashed and atomically moved to its final path."""
        content = b"download-content"
        parameters = {
            "downloadRequestId": "request-1",
            "itemId": "item-1",
            "url": "https://example.invalid/file",
            "fileName": "file.bin",
            "maxBytes": len(content),
        }
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.stream_download(
                parameters, Path(directory), opener=lambda *_args, **_kwargs: FakeResponse(content),
                resolver=public_resolver)
            self.assertEqual(len(content), result["sizeBytes"])
            self.assertEqual(content, (Path(directory) / "request-1" / "file.bin").read_bytes())

    def test_removes_staging_file_when_limit_is_exceeded(self):
        """An oversized stream must not leave a final or staging file."""
        parameters = {
            "downloadRequestId": "request-2",
            "itemId": "item-2",
            "url": "https://example.invalid/file",
            "fileName": "file.bin",
            "maxBytes": 3,
        }
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                MODULE.stream_download(
                    parameters, Path(directory), opener=lambda *_args, **_kwargs: FakeResponse(b"large"),
                    resolver=public_resolver)
            self.assertEqual([], list((Path(directory) / "request-2").iterdir()))

    def test_rejects_path_traversal(self):
        """A file name cannot escape the request directory."""
        with self.assertRaises(ValueError):
            MODULE.validated_name("../outside")

    def test_rejects_private_network_destination(self):
        """A message-controlled URL cannot target loopback or private services."""
        with self.assertRaisesRegex(ValueError, "non-public"):
            MODULE.validated_url("http://internal.example/secret", resolver=lambda *_args, **_kwargs: [
                (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 80))])

    def test_accepts_trusted_twimg_without_local_dns_resolution(self):
        """X 解析器生成的 HTTPS 媒体允许绕过受代理污染的本机 DNS。"""
        def resolver(*_args, **_kwargs):
            self.fail("trusted host must not use local DNS")

        self.assertEqual("https://pbs.twimg.com/media/test.jpg",
                         MODULE.validated_url("https://pbs.twimg.com/media/test.jpg", resolver,
                                              MODULE.trusted_host_suffix(".twimg.com")))

    def test_rejects_untrusted_suffix_and_cross_domain_redirect(self):
        """信任后缀仅限 twimg 且重定向不得离开该媒体域。"""
        with self.assertRaisesRegex(ValueError, "not allowed"):
            MODULE.trusted_host_suffix(".example.com")
        with self.assertRaisesRegex(ValueError, "does not match"):
            MODULE.validated_url("https://example.com/file", public_resolver, ".twimg.com")

    def test_accepts_only_loopback_http_proxy(self):
        """Restricted downloads may use the local managed proxy only."""
        self.assertEqual("http://127.0.0.1:17890",
                         MODULE.validated_proxy("http://127.0.0.1:17890/"))
        with self.assertRaisesRegex(ValueError, "loopback"):
            MODULE.validated_proxy("http://proxy.example:7891")

    def test_reports_large_download_at_five_percent_milestones(self):
        """Large known-length streams emit a start and every five-percent milestone."""
        content = b"x" * (11 * 1024 * 1024)
        reports = []
        parameters = {"downloadRequestId": "request-progress", "itemId": "item-progress",
                      "url": "https://example.invalid/file", "fileName": "large.bin",
                      "maxBytes": len(content)}
        with tempfile.TemporaryDirectory() as directory:
            MODULE.stream_download(parameters, Path(directory),
                opener=lambda *_args, **_kwargs: FakeResponse(content), resolver=public_resolver,
                progress_reporter=lambda _request, _item, downloaded, total, percent:
                    reports.append((downloaded, total, percent)))
        self.assertEqual([0, *range(5, 101, 5)], [report[2] for report in reports])
        self.assertTrue(all(report[1] == len(content) for report in reports))

    def test_progress_http_500_does_not_restart_or_fail_download(self):
        """进度端点返回 500 时应继续当前内容流并生成正确结果。"""
        failure = HTTPError("http://progress.invalid", 500, "sensitive-response", {}, None)
        self.assert_progress_failure_is_best_effort(failure)

    def test_progress_timeout_does_not_restart_or_fail_download(self):
        """进度端点超时时应继续当前内容流并生成正确结果。"""
        self.assert_progress_failure_is_best_effort(TimeoutError("sensitive-timeout"))

    def test_progress_callback_uses_short_timeout(self):
        """进度回调应使用短超时且不做脚本内重试。"""
        response = FakeResponse(b"")
        with patch.object(MODULE, "urlopen", return_value=response) as opener, \
                patch.dict(MODULE.os.environ, {
                    "DOWNLOAD_INGESTION_URL": "http://127.0.0.1:23220",
                    "DOWNLOAD_INTERNAL_TOKEN": "token",
                }, clear=True):
            MODULE.report_progress("request", "item", 1, 10, 10)
        self.assertEqual(MODULE.PROGRESS_REPORT_TIMEOUT_SECONDS,
                         opener.call_args.kwargs["timeout"])
        self.assertEqual(1, opener.call_count)

    def assert_progress_failure_is_best_effort(self, failure):
        """验证任意进度故障均不会破坏已打开的下载流。"""
        content = b"x" * (11 * 1024 * 1024)
        expected_sha256 = hashlib.sha256(content).hexdigest()
        parameters = {
            "downloadRequestId": "request-progress-failure",
            "itemId": "item-progress-failure",
            "url": "https://example.invalid/file",
            "fileName": "large.bin",
            "maxBytes": len(content),
            "expectedSha256": expected_sha256,
        }
        calls = []

        def failing_reporter(*args):
            calls.append(args)
            raise failure

        warning = io.StringIO()
        with tempfile.TemporaryDirectory() as directory, redirect_stderr(warning):
            root = Path(directory)
            result = MODULE.stream_download(
                parameters, root, opener=lambda *_args, **_kwargs: FakeResponse(content),
                resolver=public_resolver, progress_reporter=failing_reporter)

            self.assertEqual(content, (root / "request-progress-failure" / "large.bin").read_bytes())
        self.assertEqual(1, len(calls))
        self.assertEqual(len(content), result["sizeBytes"])
        self.assertEqual(expected_sha256, result["contentSha256"])
        self.assertEqual(MODULE.PROGRESS_FAILURE_MESSAGE + "\n", warning.getvalue())
        self.assertNotIn("sensitive", warning.getvalue())


def public_resolver(*_args, **_kwargs):
    """Resolve test hosts to one deterministic public documentation address."""
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))]


if __name__ == "__main__":
    unittest.main()
