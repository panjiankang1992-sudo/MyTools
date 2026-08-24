import importlib.util
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_copy_native_tree", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self):
        self.events = []
        self.child_reads = 0

    def operation(self, operation_id):
        if operation_id == "parent":
            return {"id": "parent", "providerId": "provider", "operationType": "COPY_TREE_NATIVE",
                    "status": "RUNNING", "sourcePath": "books", "maximumObjects": 10}
        self.child_reads += 1
        return {"id": operation_id, "status": "SUCCEEDED"}

    def list(self, provider_id, path):
        if path == "books":
            return [{"path": "books/fiction", "name": "fiction", "directory": True,
                     "sizeBytes": 0},
                    {"path": "books/index.txt", "name": "index.txt", "directory": False,
                     "sizeBytes": 4}]
        return [{"path": "books/fiction/a.epub", "name": "a.epub", "directory": False,
                 "sizeBytes": 8}]

    def merge(self, operation_id, items):
        self.events.append(("merge", [item["path"] for item in items]))
        return {}

    def create_child(self, operation_id, source_path):
        self.events.append(("child", source_path))
        return {"id": source_path, "status": "RUNNING"}

    def finish(self, operation_id):
        self.events.append(("finish", operation_id))
        return {"status": "SUCCEEDED"}


def test_freezes_complete_tree_before_creating_children():
    client = FakeClient()

    result = MODULE.execute({"operationId": "parent"}, client, pause=lambda _: None)

    event_names = [event[0] for event in client.events]
    assert event_names == ["merge", "merge", "child", "child", "finish"]
    assert result == {"operationId": "parent", "status": "SUCCEEDED", "itemCount": 3,
                      "childCount": 2}
