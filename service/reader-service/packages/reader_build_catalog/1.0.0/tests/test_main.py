import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


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


if __name__ == "__main__":
    unittest.main()
