import importlib.util
import io
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_finish_checksum", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self):
        return b'{"status":"CANCELLED"}'


def test_execute_maps_cancel_step_to_terminal_state():
    requests = []

    def opener(request, timeout):
        requests.append((request, timeout))
        return Response()

    result = MODULE.execute({"stepName": "on_cancel", "parameters": {
        "checksumOperationId": "operation-id"}}, "http://storage", "token", opener)

    assert result == {"status": "CANCELLED"}
    assert json.loads(requests[0][0].data)["errorCode"] == "STORAGE_TASK_CANCELLED"
