from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from uuid import uuid4

import pytest

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_storage_object", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeStorage:
    def __init__(self, content=b"managed"):
        self.content = content
        self.published = None

    def download(self, _uri, target, maximum):
        assert len(self.content) <= maximum
        target.write_bytes(self.content)
        return len(self.content)

    def publish(self, path, root, relative, key, size, digest):
        self.published = (path.read_bytes(), root, relative, key, size, digest)
        return f"storage://{root}/{relative}"


def test_storage_object_is_verified_and_published(tmp_path):
    request_id = str(uuid4())
    storage = FakeStorage()
    result = MODULE.execute({"downloadRequestId": request_id, "itemId": "item-1",
                             "sourceStorageUri": "storage://legacy/a.bin",
                             "fileName": "a.bin"}, tmp_path, storage)
    assert result["storageUri"].startswith("storage://managed/")
    assert result["sizeBytes"] == len(b"managed")
    assert storage.published[0] == b"managed"


def test_storage_object_rejects_checksum_mismatch(tmp_path):
    with pytest.raises(ValueError, match="checksum mismatch"):
        MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "item-1",
                        "sourceStorageUri": "storage://legacy/a.bin", "fileName": "a.bin",
                        "expectedSha256": "0" * 64}, tmp_path, FakeStorage())


def test_storage_object_routes_imported_album_by_received_date(tmp_path):
    storage = FakeStorage(b"image")
    MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "item-1",
                    "sourceStorageUri": "storage://managed/staging/photo.jpg",
                    "fileName": "photo.jpg", "assetMimeType": "image/jpeg",
                    "receivedAt": "2026-08-26T15:53:08+08:00",
                    "albumFolder": "Beach--12345678"}, tmp_path, storage)
    assert storage.published[2] == "media/202608/20260826/Beach--12345678/photo.jpg"
