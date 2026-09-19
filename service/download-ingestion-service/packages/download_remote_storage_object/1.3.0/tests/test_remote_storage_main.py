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
    assert result["storageUri"].startswith("storage://managed/")

def test_chinese_directory_is_not_url_encoded_and_can_be_created(tmp_path):
    request_id = str(uuid4())
    directory = "\u4e2d" * 47
    storage = FakeStorage()
    MODULE.execute({"downloadRequestId": request_id, "itemId": "remote-2",
        "sourceProviderId": str(uuid4()), "sourcePath": "ready/op/file.jpg", "fileName": "file.jpg",
        "destinationRelativePath": directory + "/file.jpg", "expectedSize": 6}, tmp_path, storage)
    relative = storage.published[2]
    assert relative == request_id + "/" + directory + "/file.jpg"
    assert "%" not in relative
    destination = tmp_path / relative
    destination.parent.mkdir(parents=True)
    destination.write_bytes(storage.content)
    assert destination.read_bytes() == storage.content
    assert storage.published[3].startswith("download-remote-v3:")

def test_long_utf8_components_keep_extension_and_distinct_hashes(tmp_path):
    first = "\u4e2d" * 200 + "a.jpg"
    second = "\u4e2d" * 200 + "b.jpg"
    safe = MODULE.local_component(first)
    assert len(safe.encode()) <= 240 and safe.endswith(".jpg")
    assert safe == MODULE.local_component(first)
    assert safe != MODULE.local_component(second)
    (tmp_path / safe).write_bytes(b"ok")

def test_literal_percent_sequences_are_not_decoded():
    assert "%" not in MODULE.local_component("100%20done.txt")
    assert MODULE.local_component("100%20done.txt") != MODULE.local_component("100 done.txt")

@pytest.mark.parametrize("path", ["../x", "/x", "x//y", "x/./y", "x\\y", "x/\x00y", "x/\ny"])
def test_invalid_destinations_fail_before_network(tmp_path, path):
    storage = FakeStorage()
    with pytest.raises(ValueError, match="destinationRelativePath"):
        MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "remote-1",
            "sourceProviderId": str(uuid4()), "sourcePath": "ready/a", "fileName": "a.bin",
            "destinationRelativePath": path}, tmp_path, storage)
    assert storage.source is None and storage.published is None
def test_remote_object_rejects_size_mismatch(tmp_path):
    with pytest.raises(ValueError, match="size mismatch"):
        MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "remote-1",
            "sourceProviderId": str(uuid4()), "sourcePath": "ready/a", "fileName": "a.bin",
            "expectedSize": 9}, tmp_path, FakeStorage())

@pytest.mark.parametrize("content,name,mime", [
    (b"GIF89a" + b"\0" * 20, "animation.bin", "image/gif"),
    (b"\0\0\0\x20ftypisom" + b"\0" * 20, "clip.bin", "video/mp4"),
    (b"\xff\xd8\xff" + b"\0" * 20, "photo.bin", "image/jpeg"),
    (b"\x89PNG\r\n\x1a\n", "photo.bin", "image/png"),
    (b"remote", "unknown.bin", "application/octet-stream"),
])
def test_verified_output_carries_media_type(tmp_path, content, name, mime):
    storage = FakeStorage(content)
    result = MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "typed",
        "sourceProviderId": str(uuid4()), "sourcePath": "ready/" + name, "fileName": name}, tmp_path, storage)
    assert result["mimeType"] == mime

@pytest.mark.parametrize("name", ["a b.jpg", "a#b.jpg", "a?b.jpg", "a%b.jpg", "a[b].jpg", "a{b}.jpg"])
def test_uri_unsafe_names_are_bounded_distinct_and_keep_display_name(tmp_path, name):
    storage = FakeStorage()
    result = MODULE.execute({"downloadRequestId": str(uuid4()), "itemId": "uri-safe",
        "sourceProviderId": str(uuid4()), "sourcePath": "ready/" + name, "fileName": name}, tmp_path, storage)
    assert result["fileName"] == name
    assert storage.source[1] == "ready/" + name
    relative = storage.published[2]
    assert not any(c in relative for c in ' %#?[]{}')
    assert relative.endswith(".jpg")
    assert MODULE.local_component(name) != MODULE.local_component(name.replace(" ", "_").replace("#", "_").replace("?", "_").replace("%", "_").replace("[", "_").replace("{", "_"))
