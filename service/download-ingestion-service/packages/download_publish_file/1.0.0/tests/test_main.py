import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile

SERVICE_ROOT = Path(__file__).parents[5]
sys.path.insert(0, str(SERVICE_ROOT / "task-executor-service" / "sdk" / "python"))
SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_publish_file", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self):
        self.arguments = None

    def publish(self, *arguments):
        self.arguments = arguments
        return "storage://downloads/downloads/request/item/file.bin"


def test_reverifies_and_publishes_download():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        target = root / "request" / "file.bin"
        target.parent.mkdir()
        target.write_bytes(b"content")
        digest = hashlib.sha256(b"content").hexdigest()
        context = {"parameters": {"storageRoot": "downloads"}, "stepOutputs": {
            "download_asset": {"requestId": "request", "itemId": "item", "fileName": "file.bin",
                               "relativePath": "request/file.bin", "sizeBytes": 7,
                               "contentSha256": digest}}}
        storage = Storage()
        result = MODULE.execute(context, root, storage)
        assert result["storageUri"].startswith("storage://downloads/")
        assert storage.arguments[4] == 7
        assert storage.arguments[5] == digest


def test_rejects_changed_download():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        target = root / "request" / "file.bin"
        target.parent.mkdir()
        target.write_bytes(b"changed")
        context = {"parameters": {}, "stepOutputs": {"download_asset": {
            "requestId": "request", "itemId": "item", "fileName": "file.bin",
            "relativePath": "request/file.bin", "sizeBytes": 7, "contentSha256": "0" * 64}}}
        try:
            MODULE.execute(context, root, Storage())
        except ValueError as exception:
            assert "integrity" in str(exception)
        else:
            raise AssertionError("changed file must be rejected")
