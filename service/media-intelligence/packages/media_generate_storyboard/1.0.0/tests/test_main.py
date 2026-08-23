"""Tests for storyboard frame generation."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_generate_storyboard", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaGenerateStoryboardTest(unittest.TestCase):
    """Validate bounded positioning and artifact metadata."""

    def test_generates_evenly_spaced_frames(self):
        """Frame generation uses probe duration and returns hashed artifacts."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "video.mp4"
            source.write_bytes(b"video")

            def fake_frame(_source, target, position_ms):
                target.write_bytes(f"frame-{position_ms}".encode())

            result = MODULE.execute(
                {"assetId": "7", "contentSha256": "a" * 64, "sourcePath": str(source), "frameCount": 3},
                {"probe": {"durationMs": 4000}}, root / "work", fake_frame)

            self.assertEqual([1000, 2000, 3000], [frame["positionMs"] for frame in result["frames"]])
            self.assertTrue(all(Path(frame["artifactPath"]).is_file() for frame in result["frames"]))

    def test_rejects_more_than_twelve_frames(self):
        """The storyboard contract caps model inputs at twelve frames."""
        with self.assertRaises(ValueError):
            MODULE.frame_positions(1000, 13)


if __name__ == "__main__":
    unittest.main()
