import importlib.util
import json
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_abort_native_tree", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return b'{"status":"CANCELLED"}'


def test_maps_cancel_step_to_parent_abort():
    captured = {}

    def opener(request, timeout):
        captured["url"] = request.full_url
        captured["payload"] = json.loads(request.data)
        captured["timeout"] = timeout
        return Response()

    result = MODULE.execute({"stepName": "on_cancel", "parameters": {
        "operationId": "00000000-0000-4000-8000-000000000001"}}, "http://storage", "token", opener)

    assert captured["url"].endswith("/native-tree/abort")
    assert captured["payload"]["status"] == "CANCELLED"
    assert result == {"status": "CANCELLED"}
