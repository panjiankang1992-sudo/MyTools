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

    def test_registers_managed_download_with_storage_gateway_provider(self):
        client = Client()
        MODULE.execute({
            "parameters": {"downloadRequestId": "00000000-0000-4000-8000-000000000004"},
            "stepOutputs": {"download_asset": {"storageUri": "storage://downloads/r/a.bin",
                                                 "contentSha256": "d" * 64,
                                                 "sizeBytes": 12}}
        }, client)
        self.assertEqual("STORAGE_GATEWAY", client.payload["location"]["providerType"])

    def test_prefers_durable_published_download(self):
        client = Client()
        MODULE.execute({
            "parameters": {"downloadRequestId": "00000000-0000-4000-8000-000000000005"},
            "stepOutputs": {"download_asset": {"relativePath": "r/a.bin",
                "contentSha256": "e" * 64, "sizeBytes": 12},
                "publish_asset": {"storageUri": "storage://downloads/r/a.bin",
                "contentSha256": "e" * 64, "sizeBytes": 12}}
        }, client)
        self.assertEqual("STORAGE_GATEWAY", client.payload["location"]["providerType"])
        self.assertEqual("storage://downloads/r/a.bin", client.payload["location"]["storageUri"])

    def test_registers_published_media_scan_with_managed_provider(self):
        client = Client()
        MODULE.execute({
            "parameters": {"assetId": "scan:one", "ownerId": 7,
                "assetSourceType": "MEDIA_SCAN", "assetSourceBusinessId": "scan:one",
                "assetMimeType": "video/mp4", "assetProviderType": "STORAGE_GATEWAY",
                "assetProviderVersion": "1"},
            "stepOutputs": {"publish_asset": {"storageUri": "storage://media/scans/video.mp4",
                "contentSha256": "f" * 64, "sizeBytes": 12}}
        }, client)
        self.assertEqual("MEDIA_SCAN", client.payload["sourceType"])
        self.assertEqual("STORAGE_GATEWAY", client.payload["location"]["providerType"])
        self.assertEqual("storage://media/scans/video.mp4",
                         client.payload["location"]["storageUri"])

    def test_registers_media_probe_without_exposing_source_path(self):
        with __import__("tempfile").TemporaryDirectory() as directory:
            source = Path(directory) / "video.mp4"
            source.write_bytes(b"media")
            client = Client()
            MODULE.execute({
                "parameters": {"assetId": "42", "contentSha256": "c" * 64,
                               "sourcePath": str(source), "assetMimeType": "video/mp4"},
                "stepOutputs": {"probe": {"durationMs": 1000}},
            }, client)

            self.assertEqual("MEDIA_FILE", client.payload["sourceType"])
            self.assertEqual(5, client.payload["sizeBytes"])
            self.assertEqual("media://legacy/42", client.payload["location"]["storageUri"])
            self.assertNotIn(str(source), client.payload["location"]["storageUri"])


if __name__ == "__main__":
    unittest.main()
