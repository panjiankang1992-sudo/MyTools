import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_extract_audiobook_text", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, source):
        self.source = source
        self.published = []

    def download(self, _uri, target, _maximum):
        target.write_bytes(self.source)
        return len(self.source)

    def publish(self, path, _root, relative, key, size, digest):
        self.published.append((path.read_bytes(), relative, key, size, digest))
        return "storage://managed/" + relative


class Reader:
    def __init__(self, source, chapters):
        self.source = source
        self.chapters = chapters
        self.saved = []
        self.completed = None

    def input(self, generation_id):
        import hashlib
        return {"generationId": generation_id, "sourceStorageUri": "storage://managed/source.txt",
                "expectedBookSha256": hashlib.sha256(self.source).hexdigest(), "chapters": self.chapters}

    def save(self, _generation_id, chapters):
        self.saved.extend(chapters)

    def complete(self, generation_id):
        self.completed = generation_id
        return {"generationId": generation_id, "projectedChapterCount": len(self.saved),
                "projectedCharacterCount": sum(chapter["characterCount"] for chapter in self.saved)}


class ReaderExtractAudiobookTextTest(unittest.TestCase):

    def test_projects_normalized_chapters_with_stable_hashes(self):
        source = b"Chapter one\r\n\r\nHello\x00 world\r\n\r\n\r\nChapter two\nSecond"
        split = source.index(b"Chapter two")
        chapters = [
            {"index": 0, "title": "One", "resourceRef": "text:0", "startOffset": 0, "endOffset": split},
            {"index": 1, "title": "Two", "resourceRef": f"text:{split}", "startOffset": split,
             "endOffset": len(source)},
        ]
        storage = Storage(source)
        reader = Reader(source, chapters)
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001",
                                     "storageRoot": "managed"}, storage, reader, Path(directory))

        self.assertEqual(2, result["projectedChapterCount"])
        self.assertEqual(42, result["projectedCharacterCount"])
        self.assertEqual(2, len(reader.saved))
        self.assertEqual(b"Chapter one\n\nHello world\n", storage.published[0][0])
        self.assertEqual(64, len(reader.saved[0]["contentSha256"]))
        self.assertEqual(24, reader.saved[0]["characterCount"])
        self.assertEqual("00000000-0000-4000-8000-000000000001", reader.completed)

    def test_rejects_non_text_catalog_entries(self):
        with self.assertRaisesRegex(ValueError, "requires TXT"):
            MODULE.chapter_bytes(b"text", {"resourceRef": "epub:chapter.xhtml", "startOffset": None,
                                             "endOffset": None})

    def test_counts_unicode_code_points_instead_of_utf8_bytes(self):
        chapter = {"index": 0}
        with tempfile.TemporaryDirectory() as directory:
            published = MODULE.publish_chapter(Storage(b""), Path(directory), "managed",
                                               "00000000-0000-4000-8000-000000000001", chapter,
                                               "你好😀\n".encode("utf-8"))

        self.assertEqual(len("你好😀\n".encode("utf-8")), published["sizeBytes"])
        self.assertEqual(3, published["characterCount"])

    def test_projects_epub_spine_as_visible_text_without_markup_or_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "book.epub"
            write_epub(archive_path)
            source = archive_path.read_bytes()
            chapters = [
                {"index": 0, "title": "One", "resourceRef": "epub:OEBPS/one.xhtml",
                 "startOffset": None, "endOffset": None},
                {"index": 1, "title": "Two", "resourceRef": "epub:OEBPS/two.xhtml",
                 "startOffset": None, "endOffset": None},
            ]
            storage = Storage(source)
            reader = Reader(source, chapters)
            result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001",
                                     "storageRoot": "managed"}, storage, reader, Path(directory))

        self.assertEqual(2, result["projectedChapterCount"])
        self.assertEqual(30, result["projectedCharacterCount"])
        self.assertEqual(b"Chapter one\n\nHello world\n", storage.published[0][0])
        self.assertNotIn(b"ignore", storage.published[0][0])

    def test_rejects_epub_resource_outside_frozen_spine(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "book.epub"
            write_epub(archive_path)
            source = archive_path.read_bytes()
            chapters = [{"index": 0, "title": "Outside", "resourceRef": "epub:OEBPS/outside.xhtml",
                         "startOffset": None, "endOffset": None}]
            with self.assertRaisesRegex(ValueError, "frozen spine"):
                MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001",
                                "storageRoot": "managed"}, Storage(source), Reader(source, chapters),
                               Path(directory))


def write_epub(path: Path) -> None:
    """Write a bounded EPUB fixture with two visible spine documents and one non-spine resource."""
    container = """<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>"""
    package = """<?xml version=\"1.0\"?><package><manifest>
        <item id=\"one\" href=\"one.xhtml\"/><item id=\"two\" href=\"two.xhtml\"/>
        <item id=\"outside\" href=\"outside.xhtml\"/></manifest>
        <spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>"""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("OEBPS/book.opf", package)
        archive.writestr("OEBPS/one.xhtml",
                         "<html><head><title>Ignore</title><script>ignore()</script></head>"
                         "<body><h1>Chapter one</h1><p>Hello <b>world</b></p></body></html>")
        archive.writestr("OEBPS/two.xhtml", "<html><body><p>Second</p></body></html>")
        archive.writestr("OEBPS/outside.xhtml", "<html><body><p>Outside</p></body></html>")


if __name__ == "__main__":
    unittest.main()
