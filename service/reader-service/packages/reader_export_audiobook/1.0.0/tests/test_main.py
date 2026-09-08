import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_export_audiobook", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, sources):
        self.sources = sources
        self.published = []

    def download(self, uri, target, maximum):
        source = self.sources[uri]
        assert len(source) <= maximum
        target.write_bytes(source)
        return len(source)

    def publish(self, path, _root, relative, key, size, digest):
        self.published.append((path.read_bytes(), relative, key, size, digest))
        return "storage://managed/" + relative


class Reader:
    def __init__(self, input_data):
        self.input_data = input_data
        self.completed = None

    def input(self, _export_id):
        return self.input_data

    def complete(self, export_id, result):
        self.completed = (export_id, result)
        return {"id": export_id}


class ReaderExportAudiobookTest(unittest.TestCase):

    def test_builds_verified_zip_without_storage_locations(self):
        first = b"ID3-first"
        second = b"ID3-second"
        export_id = "00000000-0000-4000-8000-000000000801"
        generation_id = "00000000-0000-4000-8000-000000000802"
        source_one = "storage://managed/audiobooks/one.mp3"
        source_two = "storage://managed/audiobooks/two.mp3"
        input_data = {"exportId": export_id, "generationId": generation_id, "generationVersion": 2,
                      "format": "ZIP", "chapters": [
                          {"index": 0, "title": "第一章/开始", "storageUri": source_one,
                           "contentSha256": hashlib.sha256(first).hexdigest(), "sizeBytes": len(first),
                           "format": "mp3"},
                          {"index": 1, "title": "第二章", "storageUri": source_two,
                           "contentSha256": hashlib.sha256(second).hexdigest(), "sizeBytes": len(second),
                           "format": "mp3"}]}
        storage = Storage({source_one: first, source_two: second})
        reader = Reader(input_data)
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"exportId": export_id, "ownerId": 10, "storageRoot": "managed"},
                                    storage, reader, Path(directory))

        self.assertEqual(export_id, result["exportId"])
        self.assertEqual(2, result["chapterCount"])
        self.assertEqual(1, len(storage.published))
        archive = storage.published[0][0]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "export.zip"
            path.write_bytes(archive)
            with zipfile.ZipFile(path) as output:
                names = output.namelist()
                metadata = json.loads(output.read("metadata.json"))
                self.assertIn("chapters/00001-第一章 开始.mp3", names)
                self.assertIn("chapters/00002-第二章.mp3", names)
                self.assertNotIn("storageUri", output.read("metadata.json").decode("utf-8"))
                self.assertEqual(generation_id, metadata["generationId"])
        self.assertEqual(2, reader.completed[1]["chapterCount"])

    def test_rejects_checksum_mismatch_before_publish(self):
        source = b"ID3-source"
        export_id = "00000000-0000-4000-8000-000000000803"
        input_data = {"exportId": export_id, "generationId": "00000000-0000-4000-8000-000000000804",
                      "generationVersion": 1, "format": "ZIP", "chapters": [
                          {"index": 0, "title": "chapter", "storageUri": "storage://managed/chapter.mp3",
                           "contentSha256": "a" * 64, "sizeBytes": len(source), "format": "mp3"}]}
        storage = Storage({"storage://managed/chapter.mp3": source})
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "checksum"):
                MODULE.execute({"exportId": export_id, "ownerId": 10, "storageRoot": "managed"}, storage,
                               Reader(input_data), Path(directory))
        self.assertEqual([], storage.published)


if __name__ == "__main__":
    unittest.main()
