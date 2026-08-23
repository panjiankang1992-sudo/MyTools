"""Tests for bounded video descriptions."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_describe_video", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaDescribeVideoTest(unittest.TestCase):
    """Validate fallback output and storyboard input bounds."""

    def test_uses_metadata_fallback_without_model_configuration(self):
        """Disabled inference still produces a stable structured result."""
        result = MODULE.execute(
            {"assetId": "9", "contentSha256": "b" * 64, "sourcePath": "/media/video.mp4"},
            {"probe": {"durationMs": 61000, "video": {"width": 1920, "height": 1080}}},
        )
        self.assertEqual("METADATA_FALLBACK", result["generationMode"])
        self.assertIn("61 seconds", result["description"])
        self.assertEqual(0, result["frameCount"])

    def test_accepts_at_most_twelve_bounded_frames(self):
        """Only valid, bounded frame files are sent to the model."""
        with tempfile.TemporaryDirectory() as directory:
            frames = []
            for index in range(13):
                path = Path(directory) / f"{index}.jpg"
                path.write_bytes(b"jpeg")
                frames.append({"artifactPath": str(path)})
            paths = MODULE.storyboard_paths({"generate_storyboard": {"frames": frames}})
            self.assertEqual(12, len(paths))


if __name__ == "__main__":
    unittest.main()
