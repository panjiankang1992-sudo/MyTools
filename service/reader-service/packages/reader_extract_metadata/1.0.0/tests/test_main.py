import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_extract_metadata", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, content):
        self.content = content

    def download(self, storage_uri, target, maximum_bytes):
        target.write_bytes(self.content)
        return len(self.content)

    def publish(self, *args):
        return "storage://managed/cover.png"


class ReaderExtractMetadataTest(unittest.TestCase):

    def test_extracts_text_metadata_from_previous_step_output(self):
        content = "[Original filename] Example_Book.txt\nChapter 1\nText\nChapter 2\nMore".encode()
        context = {"parameters": {"requestId": "00000000-0000-4000-8000-000000000001",
                                  "storageRoot": "managed"},
                   "stepOutputs": {"import_ebook": {"storageUri": "storage://managed/book.txt",
                                                      "title": "Fallback"}}}
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute(context, Storage(content), Path(directory))

        self.assertEqual("READY", result["status"])
        self.assertEqual("Example Book", result["title"])
        self.assertEqual(2, result["chapterCount"])
        self.assertGreater(result["wordCount"], 0)

    def test_extracts_epub_opf_without_external_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "example.epub"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("META-INF/container.xml", """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles>
                    </container>
                    """)
                archive.writestr("OEBPS/content.opf", """
                    <package xmlns="http://www.idpf.org/2007/opf"
                             xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <metadata><dc:title>EPUB Book</dc:title><dc:creator>Writer</dc:creator></metadata>
                      <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="one"/></spine>
                    </package>
                    """)
            metadata, cover, extension = MODULE.extract_epub(archive_path, MODULE.base_metadata("fallback.epub"))

        self.assertEqual("READY", metadata["status"])
        self.assertEqual("EPUB Book", metadata["title"])
        self.assertEqual("Writer", metadata["author"])
        self.assertEqual(1, metadata["chapterCount"])
        self.assertIsNone(cover)
        self.assertIsNone(extension)


if __name__ == "__main__":
    unittest.main()
