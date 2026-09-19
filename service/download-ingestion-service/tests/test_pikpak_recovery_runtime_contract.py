"""验证磁力补跑入口与执行器凭证注入契约。"""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4


def test_recovery_environment_and_owner_bound_summary(monkeypatch):
    service_root = Path(__file__).resolve().parents[1]
    script = service_root / "packages/download_pikpak_magnet/1.4.0/scripts/main.py"
    spec = importlib.util.spec_from_file_location("pikpak_recovery_runtime", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    configuration = (service_root.parent / "task-executor-service/src/main/resources/application.yml").read_text()
    block = configuration.split("    download_pikpak_magnet:\n", 1)[1].split("    pikpak_watch_scan:", 1)[0]
    assert "DOWNLOAD_INTERNAL_TOKEN: ${DOWNLOAD_INTERNAL_TOKEN:}" in block
    assert "DOWNLOAD_INGESTION_URL:" in block
    request_id = str(uuid4())
    context = SimpleNamespace(parameters={"downloadRequestId": request_id, "ownerId": 7, "recoveryAttempt": 4})
    monkeypatch.setattr(module.TaskContext, "load", lambda: context)
    monkeypatch.setenv("PIKPAK_CONNECTOR_TOKEN", "test-connector-token")
    monkeypatch.setenv("DOWNLOAD_INTERNAL_TOKEN", "test-download-token")
    monkeypatch.setenv("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220")
    items = [{"itemId": "completed-item"}]

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def read(self, maximum):
            assert maximum <= 16 * 1024 * 1024
            return json.dumps({"downloadRequestId": request_id, "items": items}).encode()

    def request_summary(request, timeout):
        assert request.full_url.endswith(request_id + "/result-summary?ownerId=7")
        assert request.get_header("Authorization") == "Bearer test-download-token"
        assert timeout == 30
        return Response()

    def execute(actual_context, client, recovery_results):
        assert actual_context is context and recovery_results == items
        return {"status": "READY"}

    monkeypatch.setattr(module, "urlopen", request_summary)
    monkeypatch.setattr(module, "execute", execute)
    outputs = []
    monkeypatch.setattr(module, "write_result", outputs.append)
    module.main()
    assert outputs == [{"status": "READY"}]


def test_remote_mime_reaches_asset_registration():
    service_root = Path(__file__).resolve().parents[1]
    script = service_root.parent / "asset-registry-service/packages/asset_register_content/1.1.0/scripts/main.py"
    spec = importlib.util.spec_from_file_location("remote_asset_registration_contract", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    migration = (service_root.parent / "task-scheduler-service/src/main/resources/db/migration/V144__propagate_remote_media_type.sql").read_text()
    assert "script_version='1.1.0'" in migration and "name='download_remote_storage_object'" in migration

    class Client:
        def register(self, payload):
            assert payload["mimeType"] == "image/gif"
            return {"id": str(uuid4()), "version": 1}

    module.execute({"parameters": {"downloadRequestId": str(uuid4()), "ownerId": 7},
        "stepOutputs": {"download_remote_asset": {"mimeType": "image/gif", "sizeBytes": 7,
            "contentSha256": "a" * 64, "storageUri": "storage://managed/request/image.gif"}}}, Client())
