import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_import_managed_ebook", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, payload):
        self.payload = payload
        self.offset = 0

    def read(self, size):
        value = self.payload[self.offset:self.offset + size]
        self.offset += len(value)
        return value

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class Storage:
    def publish(self, path, root_name, relative_path, idempotency_key, size, sha256):
        self.content = path.read_bytes()
        self.relative_path = relative_path
        self.idempotency_key = idempotency_key
        return "storage://managed/" + relative_path


class ReaderImportManagedEbookTest(unittest.TestCase):
    def test_copies_verified_utf8_text_and_preserves_chapter_count(self):
        content = "Title\n\nChapter 1\nOne\n\u7b2c\u4e8c\u7ae0\nTwo\n".encode("utf-8")
        parameters = {"requestId": "00000000-0000-4000-8000-000000000001", "ownerId": 7,
                      "sourceId": "00000000-0000-4000-8000-000000000002",
                      "mediaItemId": "00000000-0000-4000-8000-000000000003",
                      "mediaAssetId": "00000000-0000-4000-8000-000000000004", "title": "Example/Book",
                      "mimeType": "text/plain", "sizeBytes": len(content),
                      "contentSha256": hashlib.sha256(content).hexdigest(), "storageRoot": "managed"}
        storage = Storage()
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(MODULE.urllib.request, "urlopen", return_value=Response(content)):
                result = MODULE.execute(parameters, storage, Path(directory), "http://media", "token")

        self.assertEqual(2, result["chapterCount"])
        self.assertEqual(content, storage.content)
        self.assertIn("Example_Book.txt", storage.relative_path)

    def test_rejects_changed_media_content(self):
        content = b"Chapter 1\nchanged\n"
        parameters = {"ownerId": 7, "mediaItemId": "00000000-0000-4000-8000-000000000003",
                      "mimeType": "text/plain", "sizeBytes": len(content), "contentSha256": "0" * 64}
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(MODULE.urllib.request, "urlopen", return_value=Response(content)):
                with self.assertRaisesRegex(ValueError, "frozen media identity"):
                    MODULE.download_ebook(parameters, Path(directory) / "ebook.txt", "http://media", "token")

    def test_copies_verified_epub_and_returns_its_spine_chapter_count(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "book.epub"
            write_epub(source)
            content = source.read_bytes()
            parameters = {"requestId": "00000000-0000-4000-8000-000000000001", "ownerId": 7,
                          "sourceId": "00000000-0000-4000-8000-000000000002",
                          "mediaItemId": "00000000-0000-4000-8000-000000000003",
                          "mediaAssetId": "00000000-0000-4000-8000-000000000004", "title": "Example/Book",
                          "mimeType": "application/epub+zip", "sizeBytes": len(content),
                          "contentSha256": hashlib.sha256(content).hexdigest(), "storageRoot": "managed"}
            storage = Storage()
            with patch.object(MODULE.urllib.request, "urlopen", return_value=Response(content)):
                result = MODULE.execute(parameters, storage, Path(directory), "http://media", "token")

        self.assertEqual("EPUB", result["format"])
        self.assertEqual(2, result["chapterCount"])
        self.assertEqual(content, storage.content)
        self.assertIn("Example_Book.epub", storage.relative_path)

    def test_rejects_epub_with_unsafe_archive_member_before_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "unsafe.epub"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("../outside.xhtml", "unsafe")
            content = source.read_bytes()
            parameters = {"ownerId": 7, "mediaItemId": "00000000-0000-4000-8000-000000000003",
                          "mimeType": "application/epub+zip", "sizeBytes": len(content),
                          "contentSha256": hashlib.sha256(content).hexdigest()}
            with patch.object(MODULE.urllib.request, "urlopen", return_value=Response(content)):
                with self.assertRaisesRegex(ValueError, "unsafe"):
                    MODULE.download_ebook(parameters, Path(directory) / "ebook.epub", "http://media", "token")


def write_epub(path: Path) -> None:
    """Write a minimal two-chapter EPUB fixture with a deterministic spine."""
    container = """<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>"""
    package = """<?xml version=\"1.0\"?><package><manifest>
        <item id=\"one\" href=\"one.xhtml\"/><item id=\"two\" href=\"two.xhtml\"/>
        </manifest><spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>"""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("OEBPS/book.opf", package)
        archive.writestr("OEBPS/one.xhtml", "<html><body><p>One</p></body></html>")
        archive.writestr("OEBPS/two.xhtml", "<html><body><p>Two</p></body></html>")


if __name__ == "__main__":
    unittest.main()
