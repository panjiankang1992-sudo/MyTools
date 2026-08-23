"""Tests for the media probe script."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_probe", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaProbeTest(unittest.TestCase):
    """Validate stable ffprobe normalization."""

    def test_normalizes_video_and_audio_streams(self):
        """The result should expose bounded media properties."""
        result = MODULE.normalize({"assetId": "42", "contentSha256": "A" * 64}, {
            "format": {"duration": "12.345", "format_name": "mov,mp4", "bit_rate": "800000"},
            "streams": [
                {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080,
                 "avg_frame_rate": "30000/1001"},
                {"codec_type": "audio", "codec_name": "aac"},
            ],
        })
        self.assertEqual(12345, result["durationMs"])
        self.assertEqual("h264", result["video"]["codec"])
        self.assertAlmostEqual(29.970, result["video"]["frameRate"], places=3)
        self.assertEqual("a" * 64, result["contentSha256"])


if __name__ == "__main__":
    unittest.main()
