"""Tests for the bounded HTTP asset download task."""

import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import socket

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
        resolver = lambda *_args, **_kwargs: self.fail("trusted host must not use local DNS")
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


def public_resolver(*_args, **_kwargs):
    """Resolve test hosts to one deterministic public documentation address."""
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))]


if __name__ == "__main__":
    unittest.main()
