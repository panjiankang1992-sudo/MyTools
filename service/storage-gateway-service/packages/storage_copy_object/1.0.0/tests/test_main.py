import importlib.util
from pathlib import Path
from uuid import uuid4

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_copy_object", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self, corrupt_target=False):
        self.corrupt_target = corrupt_target
        self.deleted = False
        self.uploaded = None

    def download(self, _operation_id, role, target, _maximum_bytes):
        value = b"corrupt" if role == "target" and self.corrupt_target else b"payload"
        target.write_bytes(value)
        import hashlib
        return len(value), hashlib.sha256(value).hexdigest()

    def upload(self, _operation_id, source, content_length, sha256):
        self.uploaded = source.read_bytes()
        return {"contentLength": content_length, "sha256": sha256}

    def delete_target(self, _operation_id):
        self.deleted = True


def test_execute_streams_and_reverifies_without_exposing_provider_fields(tmp_path):
    operation_id = str(uuid4())
    client = Client()

    result = MODULE.execute({"operationId": operation_id}, client, tmp_path, 1024)

    assert result == {"operationId": operation_id, "contentLength": 7,
                      "sha256": "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
                      "status": "SUCCEEDED"}
    assert client.uploaded == b"payload"
    assert not client.deleted


def test_execute_compensates_when_target_reverification_differs(tmp_path):
    client = Client(corrupt_target=True)

    with pytest.raises(RuntimeError, match="verification"):
        MODULE.execute({"operationId": str(uuid4())}, client, tmp_path, 1024)

    assert client.deleted


def test_execute_rejects_non_uuid_before_any_request(tmp_path):
    with pytest.raises(ValueError):
        MODULE.execute({"operationId": "../escape"}, Client(), tmp_path, 1024)
