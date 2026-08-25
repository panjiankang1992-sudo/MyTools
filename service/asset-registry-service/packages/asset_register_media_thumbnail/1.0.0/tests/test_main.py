"""Tests for media thumbnail asset registration."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("asset_register_media_thumbnail", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    """Capture managed artifact publication."""

    def publish(self, path, root, relative_path, idempotency_key, size, sha256):
        """Return a stable URI for the captured publication."""
        self.call = (path, root, relative_path, idempotency_key, size, sha256)
        return "storage://managed/" + relative_path


class Assets:
    """Capture asset and relationship registration calls."""

    def __init__(self):
        self.payloads = []

    def register(self, payload):
        """Return deterministic parent and artifact views."""
        self.payloads.append(payload)
        index = len(self.payloads)
        return {"id": f"00000000-0000-4000-8000-00000000000{index}", "version": index}

    def register_artifact(self, asset_id, payload):
        """Capture the derived relationship."""
        self.link = (asset_id, payload)
        return {"id": asset_id, "version": 2}


class MediaThumbnailRegistrationTest(unittest.TestCase):
    """Validate durable publication and derived asset identity."""

    def test_publishes_and_links_thumbnail(self):
        """The executor path must not be persisted as the durable location."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "video.mp4"
            thumbnail = root / "thumbnail.jpg"
            source.write_bytes(b"source-content")
            thumbnail.write_bytes(b"jpeg")
            storage = Storage()
            assets = Assets()
            result = MODULE.execute({
                "parameters": {"assetId": "42", "contentSha256": "a" * 64,
                               "sourcePath": str(source), "assetMimeType": "video/mp4"},
                "stepOutputs": {"generate_thumbnail": {"artifactPath": str(thumbnail),
                                  "artifactSha256": "b" * 64, "size": 4}},
            }, storage, assets)

            self.assertEqual("MEDIA_FILE", assets.payloads[0]["sourceType"])
            self.assertEqual("MEDIA_THUMBNAIL", assets.payloads[1]["sourceType"])
            self.assertEqual("THUMBNAIL", assets.link[1]["artifactKind"])
            self.assertTrue(result["storageUri"].startswith("storage://managed/"))
            self.assertNotIn(str(root), result["storageUri"])

    def test_uses_materialized_size_without_legacy_source_parameter(self):
        """New analysis tasks must consume the integrity-verified materialization output."""
        with tempfile.TemporaryDirectory() as directory:
            thumbnail = Path(directory) / "thumbnail.jpg"
            thumbnail.write_bytes(b"jpeg")
            storage = Storage()
            assets = Assets()
            MODULE.execute({
                "parameters": {"assetId": "42", "assetRegistryId":
                               "00000000-0000-4000-8000-000000000001",
                               "contentSha256": "a" * 64, "assetMimeType": "video/mp4"},
                "stepOutputs": {
                    "materialize_input": {"sizeBytes": 1234},
                    "generate_thumbnail": {"artifactPath": str(thumbnail),
                                           "artifactSha256": "b" * 64, "size": 4},
                },
            }, storage, assets)

            self.assertEqual(1234, assets.payloads[0]["sizeBytes"])


if __name__ == "__main__":
    unittest.main()
