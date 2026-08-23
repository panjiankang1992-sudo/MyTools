import importlib.util
import json
from pathlib import Path
from uuid import uuid4

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_complete_native_copy", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self):
        return json.dumps({"status": "SUCCEEDED"}).encode()


def test_complete_uses_only_uuid_and_success_terminal_state():
    operation_id = str(uuid4())
    captured = {}

    def opener(request, timeout):
        captured.update(url=request.full_url, body=json.loads(request.data), timeout=timeout)
        return Response()

    result = MODULE.execute({"parameters": {"operationId": operation_id}},
                            "http://storage", "token", opener)

    assert result == {"status": "SUCCEEDED"}
    assert captured["url"].endswith(f"/{operation_id}/finish")
    assert captured["body"] == {"status": "SUCCEEDED", "errorCode": None}
