"""Tests for the media thumbnail script."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_generate_thumbnail", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaGenerateThumbnailTest(unittest.TestCase):
    """Validate deterministic artifact metadata."""

    def test_builds_content_addressed_result(self):
        """The result should hash the generated artifact bytes."""
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "thumbnail.jpg"
            target.write_bytes(b"jpeg-data")
            result = MODULE.build_result(
                {"assetId": "42", "contentSha256": "B" * 64}, target)
            self.assertEqual(9, result["size"])
            self.assertEqual("b" * 64, result["contentSha256"])
            self.assertEqual(64, len(result["artifactSha256"]))


if __name__ == "__main__":
    unittest.main()
