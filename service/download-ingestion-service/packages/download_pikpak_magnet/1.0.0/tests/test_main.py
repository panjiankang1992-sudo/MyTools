from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_pikpak_magnet", MODULE_PATH)
MODULE = module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)
class FakeClient:
    def __init__(self, states): self.states, self.created = iter(states), []
    def create(self, payload): self.created.append(payload); return next(self.states)
    def advance(self, _operation_id, _magnet_uri): return next(self.states)
class FakeContext:
    TERMINAL_STATUSES = {"FAILED", "CANCELLED", "SUCCEEDED", "TIMED_OUT"}
    def __init__(self, parameters): self.parameters, self.created = parameters, []
    def create_child(self, task_name, parameters, key, **_kwargs):
        child = SimpleNamespace(id=str(uuid4()), status="QUEUED")
        self.created.append((task_name, parameters, key, child)); return child
    def wait_child(self, child_id, _timeout): return SimpleNamespace(id=child_id, status="SUCCEEDED")
    def get_task(self, child_id): return SimpleNamespace(id=child_id, status="SUCCEEDED")
    def cancel_child(self, _child_id): raise AssertionError("successful child must not be cancelled")
def test_validate_magnet_rejects_missing_btih():
    try: MODULE.validate_magnet("magnet:?dn=no-hash")
    except ValueError: pass
    else: raise AssertionError("invalid magnet should be rejected")
def test_execute_resumes_connector_operation_until_ready():
    request_id, account_id, operation_id = uuid4(), uuid4(), uuid4()
    client = FakeClient([{"id": str(operation_id), "phase": "SUBMITTED"},
        {"id": str(operation_id), "phase": "OBSERVING", "retryAfterSeconds": 1},
        {"id": str(operation_id), "phase": "READY", "items": [{"remoteFileId": "file-1",
            "relativePath": "book/a.epub", "sizeBytes": 7, "storageProviderId": str(uuid4()),
            "storagePath": "ready/operation/book/a.epub"}]}])
    context = FakeContext({"downloadRequestId": str(request_id), "accountId": str(account_id),
        "magnetUri": "magnet:?xt=urn:btih:" + "a" * 40})
    result = MODULE.execute(context, client, sleeper=lambda _seconds: None)
    assert result["status"] == "READY" and result["items"][0]["relativePath"] == "book/a.epub"
    assert client.created[0]["idempotencyKey"] == f"download:{request_id}:pikpak"
    assert context.created[0][0] == "download_remote_storage_object"
    assert context.created[0][1]["destinationRelativePath"] == "book/a.epub"
