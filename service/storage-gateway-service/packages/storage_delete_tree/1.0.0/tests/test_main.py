import importlib.util
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_delete_tree", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self, maximum=10):
        self.maximum = maximum
        self.merged = []
        self.started = False

    def operation(self, operation_id):
        return {"id": operation_id, "providerId": "provider", "operationType": "DELETE_TREE",
                "sourcePath": "trash", "status": "RUNNING", "maximumObjects": self.maximum}

    def list(self, provider_id, path):
        if path == "trash":
            return [{"path": "trash/books", "directory": True},
                    {"path": "trash/a.bin", "directory": False}]
        return [{"path": "trash/books/b.bin", "directory": False}]

    def merge(self, operation_id, items):
        self.merged.extend(items)
        return {}

    def start(self, operation_id):
        self.started = True
        return {"remoteJobId": 91}

    def status(self, operation_id):
        return {"finished": True, "success": True}


def test_freezes_bounded_tree_before_purge():
    client = FakeClient()
    result = MODULE.execute({"operationId": "operation"}, client, poll_seconds=0)

    assert client.started is True
    assert len(client.merged) == 3
    assert result == {"operationId": "operation", "remoteJobId": 91,
                      "status": "SUCCEEDED", "itemCount": 3}


def test_does_not_purge_when_object_limit_is_exceeded():
    client = FakeClient(maximum=1)

    with pytest.raises(ValueError, match="maximumObjects"):
        MODULE.execute({"operationId": "operation"}, client, poll_seconds=0)

    assert client.started is False
