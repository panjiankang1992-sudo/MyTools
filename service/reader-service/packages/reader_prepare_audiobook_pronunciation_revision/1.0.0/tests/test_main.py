import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_prepare_audiobook_pronunciation_revision", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, sources):
        self.sources = sources

    def download(self, uri, target, _maximum):
        target.write_bytes(self.sources[uri])


class Reader:
    def __init__(self, input_data):
        self.input_data = input_data
        self.completed = None

    def input(self, _generation_id):
        return self.input_data

    def complete(self, generation_id, chapter_indexes):
        self.completed = (generation_id, chapter_indexes)
        return {"generationId": generation_id, "affectedChapterCount": len(chapter_indexes)}


class ReaderPrepareAudiobookPronunciationRevisionTest(unittest.TestCase):

    def test_only_exact_term_matches_are_marked_for_minimal_regeneration(self):
        generation_id = "00000000-0000-4000-8000-000000000991"
        first = "第一章，解乐出现。".encode("utf-8")
        second = "第二章，没有该人物。".encode("utf-8")
        input_data = {"generationId": generation_id, "term": "解乐", "chapters": [
            {"index": 0, "contentSha256": hashlib.sha256(first).hexdigest(),
             "textStorageUri": "storage://managed/one.txt", "textSizeBytes": len(first)},
            {"index": 1, "contentSha256": hashlib.sha256(second).hexdigest(),
             "textStorageUri": "storage://managed/two.txt", "textSizeBytes": len(second)}]}
        reader = Reader(input_data)
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"generationId": generation_id},
                                    Storage({"storage://managed/one.txt": first,
                                             "storage://managed/two.txt": second}), reader, Path(directory))

        self.assertEqual({"generationId": generation_id, "affectedChapterCount": 1}, result)
        self.assertEqual((generation_id, [0]), reader.completed)

    def test_checksum_mismatch_does_not_invalidate_any_chapter(self):
        generation_id = "00000000-0000-4000-8000-000000000992"
        source = "解乐出现。".encode("utf-8")
        reader = Reader({"generationId": generation_id, "term": "解乐", "chapters": [
            {"index": 0, "contentSha256": "a" * 64, "textStorageUri": "storage://managed/one.txt",
             "textSizeBytes": len(source)}]})
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "checksum"):
                MODULE.execute({"generationId": generation_id}, Storage({"storage://managed/one.txt": source}),
                               reader, Path(directory))

        self.assertIsNone(reader.completed)

    def test_rejects_non_chinese_empty_or_broad_terms(self):
        for value in ("", "A", "<speak>", "解乐1"):
            with self.assertRaisesRegex(ValueError, "term"):
                MODULE.valid_term(value)


if __name__ == "__main__":
    unittest.main()
