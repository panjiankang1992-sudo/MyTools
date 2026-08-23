import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_prefetch_chapters", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Runtime:
    def install_source(self, snapshot):
        self.snapshot = snapshot

    def catalog(self, source_url, book_url):
        return {}, [{"title": "One", "url": "chapter-1"},
                    {"title": "Two", "url": "chapter-2"},
                    {"title": "Three", "url": "chapter-3"}]

    def content(self, source_url, chapter_url):
        return "Content " + chapter_url


class Reader:
    def __init__(self):
        self.rows = []

    def save(self, request_id, chapters):
        self.rows.extend(chapters)


class ReaderPrefetchChaptersTest(unittest.TestCase):

    def test_fetches_unique_sorted_selection_and_hashes_content(self):
        reader = Reader()
        result = MODULE.execute({"requestId": "00000000-0000-4000-8000-000000000001",
                                 "sourceUrl": "https://source.example", "sourceSnapshot": {},
                                 "bookUrl": "https://book.example", "chapterIndexes": [2, 0, 2]},
                                Runtime(), reader)

        self.assertEqual(2, result["requestedCount"])
        self.assertEqual([0, 2], [row["index"] for row in reader.rows])
        self.assertEqual(64, len(reader.rows[0]["sha256"]))

    def test_rejects_out_of_range_index(self):
        with self.assertRaisesRegex(ValueError, "exceeds catalog"):
            MODULE.execute({"requestId": "00000000-0000-4000-8000-000000000001",
                            "sourceUrl": "https://source.example", "sourceSnapshot": {},
                            "bookUrl": "https://book.example", "chapterIndexes": [3]}, Runtime(), Reader())


if __name__ == "__main__":
    unittest.main()
