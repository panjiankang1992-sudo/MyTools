from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from uuid import uuid4

import pytest

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_publish_text", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeStorage:
    def publish(self, path, root, relative, key, size, digest):
        self.value = (path.read_bytes(), root, relative, key, size, digest)
        return f"storage://{root}/{relative}"


def test_generated_text_is_published_as_utf8(tmp_path):
    storage = FakeStorage()
    result = MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "web:text:1",
                             "fileName": "page.txt", "content": "title\n正文"},
                            tmp_path, storage)
    assert result["sizeBytes"] == len("title\n正文".encode())
    assert storage.value[0] == "title\n正文".encode()
    assert result["storageUri"].startswith("storage://managed/")


def test_generated_text_enforces_byte_limit(tmp_path):
    with pytest.raises(ValueError, match="exceeds limit"):
        MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "web:text:1",
                        "fileName": "page.txt", "content": "a" * (MODULE.MAX_TEXT_BYTES + 1)},
                       tmp_path, FakeStorage())
