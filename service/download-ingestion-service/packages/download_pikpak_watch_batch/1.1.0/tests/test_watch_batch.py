from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest


SPEC = spec_from_file_location("download_pikpak_watch_batch", Path(__file__).parents[1] / "scripts" / "main.py")
PACKAGE = module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


def test_multi_file_indexes_are_unique_and_stable_on_retry(monkeypatch):
    request_id, batch_id, provider_id = (str(uuid4()) for _ in range(3))
    items = [
        {"relativePath": "album/b.jpg", "storagePath": "watch/album/b.jpg",
         "remoteFileId": "file-b", "storageProviderId": provider_id, "sizeBytes": 11},
        {"relativePath": "album/a.jpg", "storagePath": "watch/album/a.jpg",
         "remoteFileId": "file-a", "storageProviderId": provider_id, "sizeBytes": 17},
    ]
    calls = []
    monkeypatch.setattr(PACKAGE, "call", lambda _base, _token, method, _path: (
        {"phase": "READY", "items": items} if method == "GET" else {"phase": "ARCHIVED"}))

    def create_child(name, parameters, key, **business):
        assert name == "download_remote_storage_object"
        assert business == {"business_type": "DOWNLOAD_REQUEST", "business_id": request_id}
        calls.append((parameters, key))
        return SimpleNamespace(id=str(uuid4()))

    def wait_for_children(_context, children, _timeout):
        assert len(children) == len(items)

    monkeypatch.setattr(PACKAGE, "wait_all_or_cancel", wait_for_children)
    context = SimpleNamespace(
        parameters={"downloadRequestId": request_id, "batchId": batch_id},
        execution_id=str(uuid4()),
        create_child=create_child,
    )
    assert PACKAGE.execute(context, "http://127.0.0.1", "test-token")["status"] == "ARCHIVED"
    first = calls.copy()
    calls.clear()
    items.reverse()
    assert PACKAGE.execute(context, "http://127.0.0.1", "test-token")["status"] == "ARCHIVED"

    assert calls == first
    assert [(params["sourceIndex"], params["itemId"], params["sourcePath"]) for params, _ in first] == [
        (0, f"pikpak-watch:{batch_id}:1", "watch/album/a.jpg"),
        (1, f"pikpak-watch:{batch_id}:2", "watch/album/b.jpg"),
    ]


def test_batch_over_source_index_limit_is_rejected_before_children(monkeypatch):
    monkeypatch.setattr(PACKAGE, "call", lambda *_args: {"phase": "READY", "items": [{}] * 10001})
    context = SimpleNamespace(parameters={"downloadRequestId": str(uuid4()), "batchId": str(uuid4())})

    with pytest.raises(RuntimeError, match="invalid watcher item list"):
        PACKAGE.execute(context, "http://127.0.0.1", "test-token")
