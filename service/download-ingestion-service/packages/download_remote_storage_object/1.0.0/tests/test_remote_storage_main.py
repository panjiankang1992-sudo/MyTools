from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from uuid import uuid4
import pytest
MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_remote_storage_object", MODULE_PATH)
MODULE = module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)
class FakeStorage:
    def __init__(self, content=b"remote"): self.content, self.source, self.published = content, None, None
    def download_remote(self, provider, path, target, maximum):
        assert len(self.content) <= maximum; self.source = (provider, path); target.write_bytes(self.content); return len(self.content)
    def publish(self, path, root, relative, key, size, digest):
        self.published = (path.read_bytes(), root, relative, key, size, digest); return f"storage://{root}/{relative}"
def test_remote_object_is_verified_and_published(tmp_path):
    request_id, provider_id = str(uuid4()), str(uuid4()); storage = FakeStorage()
    result = MODULE.execute({"downloadRequestId": request_id, "itemId": "remote-1",
        "sourceProviderId": provider_id, "sourcePath": "ready/op/a.epub", "fileName": "a.epub",
        "expectedSize": 6}, tmp_path, storage)
    assert result["sizeBytes"] == 6 and storage.source == (provider_id, "ready/op/a.epub")
    assert result["storageUri"].startswith("storage://downloads/")
def test_remote_object_rejects_size_mismatch(tmp_path):
    with pytest.raises(ValueError, match="size mismatch"):
        MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "remote-1",
            "sourceProviderId": str(uuid4()), "sourcePath": "ready/a", "fileName": "a.bin",
            "expectedSize": 9}, tmp_path, FakeStorage())
