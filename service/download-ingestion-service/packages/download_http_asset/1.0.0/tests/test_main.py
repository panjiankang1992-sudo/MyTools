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

    def test_accepts_only_loopback_http_proxy(self):
        """Restricted downloads may use the local managed proxy only."""
        self.assertEqual("http://127.0.0.1:7891",
                         MODULE.validated_proxy("http://127.0.0.1:7891/"))
        with self.assertRaisesRegex(ValueError, "loopback"):
            MODULE.validated_proxy("http://proxy.example:7891")


def public_resolver(*_args, **_kwargs):
    """Resolve test hosts to one deterministic public documentation address."""
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))]


if __name__ == "__main__":
    unittest.main()
