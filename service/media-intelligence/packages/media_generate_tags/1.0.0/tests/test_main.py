"""Tests for the media tag generation script."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_generate_tags", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaGenerateTagsTest(unittest.TestCase):
    """Validate the script contract without contacting Ollama."""

    def test_generates_normalized_result(self):
        """The script should produce a bounded, deduplicated result."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.jpg"
            source.write_bytes(b"image")
            context = root / "context.json"
            result = root / "result.json"
            context.write_text(json.dumps({"parameters": {
                "sourcePath": str(source),
                "filename": "sample.jpg",
                "mimeType": "image/jpeg",
                "contentSha256": "a" * 64,
            }}), encoding="utf-8")
            model_response = {"tags": [
                {"tag_name": "nature", "tag_type": "topic", "confidence": 1.4},
                {"tag_name": "nature", "tag_type": "topic", "confidence": 0.5},
                {"tag_name": "photo", "confidence": -1},
            ]}
            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context), "TASK_RESULT_FILE": str(result)}), \
                    patch.object(MODULE, "call_model", return_value=model_response):
                MODULE.main()
            generated = json.loads(result.read_text(encoding="utf-8"))
            self.assertEqual("a" * 64, generated["contentSha256"])
            self.assertEqual(["nature", "photo"], [tag["name"] for tag in generated["tags"]])
            self.assertEqual([1.0, 0.0], [tag["confidence"] for tag in generated["tags"]])

    def test_uses_download_step_output_as_tagging_input(self):
        """Downloaded files should reuse the common media-intelligence tagger."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            downloaded = root / "request" / "clip.mp4"
            downloaded.parent.mkdir()
            downloaded.write_bytes(b"video")
            context = root / "context.json"
            context.write_text(json.dumps({"parameters": {"itemId": "item-1"}, "stepOutputs": {
                "download_asset": {"relativePath": "request/clip.mp4", "fileName": "clip.mp4",
                                   "contentSha256": "c" * 64}}}), encoding="utf-8")
            with patch.dict(os.environ, {"TASK_CONTEXT_FILE": str(context),
                                         "DOWNLOAD_DESTINATION_ROOT": str(root)}):
                parameters = MODULE.load_parameters()
            self.assertEqual(str(downloaded), parameters["sourcePath"])
            self.assertEqual("video/mp4", parameters["mimeType"])
            self.assertEqual("c" * 64, parameters["contentSha256"])


if __name__ == "__main__":
    unittest.main()
