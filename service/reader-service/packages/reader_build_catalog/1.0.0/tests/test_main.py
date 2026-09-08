import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_build_catalog", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def download(self, storage_uri, target, maximum_bytes):
        content = "Header\nChapter 1\nFirst\nChapter 2\nSecond\n".encode()
        target.write_bytes(content)
        return len(content)


class EpubStorage:
    def __init__(self, content):
        self.content = content

    def download(self, storage_uri, target, maximum_bytes):
        target.write_bytes(self.content)
        return len(self.content)


class Writer:
    def __init__(self):
        self.entries = []
        self.replace_values = []

    def save(self, request_id, entries, replace):
        self.entries.extend(entries)
        self.replace_values.append(replace)


class ReaderBuildCatalogTest(unittest.TestCase):

    def test_builds_byte_bounded_text_catalog_and_replaces_first_batch(self):
        context = {"parameters": {"requestId": "00000000-0000-4000-8000-000000000001"},
                   "stepOutputs": {"import_ebook": {"storageUri": "storage://managed/book.txt",
                                                      "title": "Book"}}}
        writer = Writer()
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute(context, Storage(), writer, Path(directory))

        self.assertEqual(2, result["entryCount"])
        self.assertEqual([True], writer.replace_values)
        self.assertEqual("Chapter 1", writer.entries[0]["title"])
        self.assertLess(writer.entries[0]["startOffset"], writer.entries[0]["endOffset"])

    def test_infers_epub_format_from_import_storage_uri_and_persists_spine_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "book.epub"
            write_epub(archive_path)
            context = {"parameters": {"requestId": "00000000-0000-4000-8000-000000000001"},
                       "stepOutputs": {"import_ebook": {"storageUri": "storage://managed/book.epub",
                                                          "title": "Book"}}}
            writer = Writer()
            result = MODULE.execute(context, EpubStorage(archive_path.read_bytes()), writer, Path(directory))

        self.assertEqual("EPUB", result["format"])
        self.assertEqual(2, result["entryCount"])
        self.assertEqual("epub:OEBPS/one.xhtml", writer.entries[0]["resourceRef"])

    def test_returns_managed_import_contract_when_import_output_is_available(self):
        context = {
            "parameters": {"requestId": "00000000-0000-4000-8000-000000000001"},
            "stepOutputs": {"import_ebook": {
                "sourceId": "00000000-0000-4000-8000-000000000002",
                "title": "Book",
                "format": "TXT",
                "chapterCount": 2,
                "size": 42,
                "sha256": "a" * 64,
                "storageUri": "storage://managed/book.txt",
            }},
        }
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute(context, Storage(), Writer(), Path(directory))

        self.assertEqual("00000000-0000-4000-8000-000000000002", result["sourceId"])
        self.assertEqual(2, result["chapterCount"])
        self.assertNotIn("entryCount", result)


def write_epub(path: Path) -> None:
    """Write a minimal EPUB fixture with an ordered two-item spine."""
    container = """<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>"""
    package = """<?xml version=\"1.0\"?><package><manifest>
        <item id=\"one\" href=\"one.xhtml\"/><item id=\"two\" href=\"two.xhtml\"/>
        </manifest><spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>"""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("OEBPS/book.opf", package)
        archive.writestr("OEBPS/one.xhtml", "<html><head><title>One</title></head><body/></html>")
        archive.writestr("OEBPS/two.xhtml", "<html><head><title>Two</title></head><body/></html>")


if __name__ == "__main__":
    unittest.main()
