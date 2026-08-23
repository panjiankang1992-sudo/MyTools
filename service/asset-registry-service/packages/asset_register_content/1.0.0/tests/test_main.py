import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("asset_register_content", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def register(self, payload):
        self.payload = payload
        return {"id": "00000000-0000-4000-8000-000000000001", "version": 2}


class AssetRegisterContentTest(unittest.TestCase):

    def test_registers_only_verified_reader_output(self):
        client = Client()
        result = MODULE.execute({
            "parameters": {"requestId": "00000000-0000-4000-8000-000000000002", "ownerId": 7},
            "stepOutputs": {"import_ebook": {"storageUri": "storage://managed/ebooks/book.txt",
                                               "sha256": "a" * 64, "size": 1024}}
        }, client)

        self.assertEqual(2, result["version"])
        self.assertEqual("READER_EBOOK", client.payload["sourceType"])
        self.assertEqual("storage://managed/ebooks/book.txt", client.payload["location"]["storageUri"])
        self.assertNotIn("sourceSnapshot", client.payload)

    def test_normalizes_download_output_without_physical_path(self):
        client = Client()
        MODULE.execute({
            "parameters": {"downloadRequestId": "00000000-0000-4000-8000-000000000003", "ownerId": 9},
            "stepOutputs": {"download_asset": {"relativePath": "request/file name.bin",
                                                 "contentSha256": "b" * 64, "sizeBytes": 2048}}
        }, client)

        self.assertEqual("DOWNLOAD", client.payload["sourceType"])
        self.assertEqual("DOWNLOAD_EXECUTOR", client.payload["location"]["providerType"])
        self.assertEqual("download://executor/request/file%20name.bin",
                         client.payload["location"]["storageUri"])


if __name__ == "__main__":
    unittest.main()
