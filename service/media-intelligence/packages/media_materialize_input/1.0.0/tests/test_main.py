import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_materialize_input", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, body: bytes):
        self.body = io.BytesIO(body)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, size=-1):
        return self.body.read(size)


class MediaMaterializeInputTest(unittest.TestCase):
    def test_execute_streams_verified_storage_object(self):
        content = b"bounded-media"
        digest = hashlib.sha256(content).hexdigest()
        asset_id = "00000000-0000-4000-8000-000000000001"
        asset = {
            "id": asset_id,
            "status": "ACTIVE",
            "contentSha256": digest,
            "sizeBytes": len(content),
            "locations": [{"providerType": "STORAGE_GATEWAY", "availability": "AVAILABLE",
                           "storageUri": "storage://managed/media/video.mp4"}],
        }

        def opener(request, timeout):
            del timeout
            if "/internal/v1/assets/" in request.full_url:
                return Response(__import__("json").dumps(asset).encode())
            self.assertIn("rootName=managed", request.full_url)
            self.assertIn("path=media/video.mp4", request.full_url)
            return Response(content)

        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("TASK_WORK_DIR")
            os.environ["TASK_WORK_DIR"] = directory
            try:
                result = MODULE.execute({"parameters": {"assetRegistryId": asset_id,
                    "contentSha256": digest, "filename": "video.mp4"}}, "http://assets", "a",
                    "http://storage", "s", opener)
            finally:
                if previous is None:
                    os.environ.pop("TASK_WORK_DIR", None)
                else:
                    os.environ["TASK_WORK_DIR"] = previous
            self.assertEqual(content, Path(result["sourcePath"]).read_bytes())
            self.assertEqual("storage://managed/media/video.mp4", result["storageUri"])

    def test_select_location_rejects_non_gateway_location(self):
        with self.assertRaisesRegex(ValueError, "no available"):
            MODULE.select_location({"locations": [{"providerType": "LEGACY_MEDIA",
                "availability": "AVAILABLE", "storageUri": "file:///tmp/video.mp4"}]})

    def test_stream_content_removes_partial_file_on_digest_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "video.mp4"
            with self.assertRaisesRegex(ValueError, "integrity"):
                MODULE.stream_content("http://storage", "token", target, 3, "0" * 64, 10,
                                      lambda _request, timeout: Response(b"abc"))
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
