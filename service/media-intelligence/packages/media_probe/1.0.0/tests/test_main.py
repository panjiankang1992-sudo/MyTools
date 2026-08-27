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

    def test_materialized_source_takes_precedence(self):
        """New tasks must use the integrity-verified materialized input."""
        source = MODULE.resolve_source({"sourcePath": "/legacy/video.mp4"}, {
            "materialize_input": {"sourcePath": "/work/input/video.mp4"},
        })
        self.assertEqual(Path("/work/input/video.mp4"), source)

    def test_accepts_static_image_and_audio_only_media(self):
        """图片和纯音频也必须能进入统一媒体资产流程。"""
        image = MODULE.normalize({"assetId": "image", "contentSha256": "B" * 64}, {
            "format": {"format_name": "image2"},
            "streams": [{"codec_type": "video", "codec_name": "mjpeg", "width": 800,
                         "height": 600}],
        })
        audio = MODULE.normalize({"assetId": "audio", "contentSha256": "C" * 64}, {
            "format": {"duration": "3.5", "format_name": "mp3"},
            "streams": [{"codec_type": "audio", "codec_name": "mp3"}],
        })
        self.assertEqual(1, image["durationMs"])
        self.assertEqual(800, image["video"]["width"])
        self.assertEqual("none", audio["video"]["codec"])
        self.assertEqual(3500, audio["durationMs"])


if __name__ == "__main__":
    unittest.main()
