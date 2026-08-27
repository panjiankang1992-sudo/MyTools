import hashlib
import importlib.util
from pathlib import Path
import sys

import pytest

SERVICE_ROOT = Path(__file__).parents[5]
sys.path.insert(0, str(SERVICE_ROOT / "task-executor-service" / "sdk" / "python"))
SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_publish_scanned_file", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def publish(self, *arguments):
        self.arguments = arguments
        return "storage://media/scans/7/content/video.mp4"


def context(source: Path, content: bytes) -> dict:
    return {"parameters": {"sourcePath": str(source), "assetSourceBusinessId": "scan:one",
            "contentSha256": hashlib.sha256(content).hexdigest(), "sizeBytes": len(content),
            "ownerId": 7, "storageRoot": "media"}}


def test_reverifies_and_publishes_scanned_file(tmp_path):
    source = tmp_path / "video name.mp4"
    source.write_bytes(b"video")
    storage = Storage()
    result = MODULE.execute(context(source, b"video"), [str(tmp_path)], storage)
    assert result["storageUri"].startswith("storage://media/")
    assert storage.arguments[1] == "media"
    assert storage.arguments[2].endswith(hashlib.sha256(b"video").hexdigest() + ".mp4")
    assert storage.arguments[4] == 5


def test_rejects_changed_or_outside_file(tmp_path):
    source = tmp_path / "video.mp4"
    source.write_bytes(b"changed")
    with pytest.raises(ValueError, match="integrity"):
        MODULE.execute(context(source, b"original"), [str(tmp_path)], Storage())
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    with pytest.raises(ValueError, match="outside configured roots"):
        MODULE.execute(context(source, b"changed"), [str(allowed)], Storage())


def test_rejects_symbolic_link(tmp_path):
    source = tmp_path / "video.mp4"
    source.write_bytes(b"video")
    link = tmp_path / "link.mp4"
    link.symlink_to(source)
    with pytest.raises(ValueError, match="symbolic link"):
        MODULE.execute(context(link, b"video"), [str(tmp_path)], Storage())
