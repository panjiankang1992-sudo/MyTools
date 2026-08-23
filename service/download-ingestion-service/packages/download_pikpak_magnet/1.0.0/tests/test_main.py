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
def test_validate_magnet_rejects_missing_btih():
    try: MODULE.validate_magnet("magnet:?dn=no-hash")
    except ValueError: pass
    else: raise AssertionError("invalid magnet should be rejected")
def test_execute_resumes_connector_operation_until_ready():
    request_id, account_id, operation_id = uuid4(), uuid4(), uuid4()
    client = FakeClient([{"id": str(operation_id), "phase": "SUBMITTED"},
        {"id": str(operation_id), "phase": "OBSERVING", "retryAfterSeconds": 1},
        {"id": str(operation_id), "phase": "READY", "items": [{"remoteFileId": "file-1", "relativePath": "book/a.epub", "sizeBytes": 7}]}])
    context = SimpleNamespace(parameters={"downloadRequestId": str(request_id), "accountId": str(account_id),
        "magnetUri": "magnet:?xt=urn:btih:" + "a" * 40})
    result = MODULE.execute(context, client, sleeper=lambda _seconds: None)
    assert result["status"] == "READY" and result["items"][0]["relativePath"] == "book/a.epub"
    assert client.created[0]["idempotencyKey"] == f"download:{request_id}:pikpak"
