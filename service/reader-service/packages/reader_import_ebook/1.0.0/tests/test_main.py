import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_import_ebook", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Runtime:
    def install_source(self, snapshot):
        self.snapshot = snapshot

    def catalog(self, source_url, book_url):
        return {"name": "Example/Book", "author": "Author"}, [
            {"title": "One", "url": "chapter-1"}, {"title": "Two", "url": "chapter-2"}]

    def content(self, source_url, chapter_url):
        return "Content " + chapter_url


class Storage:
    def publish(self, path, root_name, relative_path, idempotency_key, size, sha256):
        self.content = path.read_text()
        self.relative_path = relative_path
        self.size = size
        return "storage://managed/" + relative_path


class ReaderImportEbookTest(unittest.TestCase):

    def test_streams_book_and_publishes_to_deterministic_path(self):
        storage = Storage()
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"requestId": "00000000-0000-4000-8000-000000000001",
                                     "ownerId": 7, "sourceId": "00000000-0000-4000-8000-000000000002",
                                     "sourceUrl": "https://source.example", "sourceSnapshot": {},
                                     "bookUrl": "https://book.example", "title": "Fallback",
                                     "storageRoot": "managed"}, Runtime(), storage, Path(directory))

        self.assertEqual(2, result["chapterCount"])
        self.assertEqual(len(storage.content.encode()), result["size"])
        self.assertIn("Example_Book.txt", storage.relative_path)
        self.assertIn("Content chapter-2", storage.content)


if __name__ == "__main__":
    unittest.main()
