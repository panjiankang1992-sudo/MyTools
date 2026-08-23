import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_finish_operation", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, document=None):
        self.document = document or {}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self):
        return json.dumps(self.document).encode()


def test_cancel_stops_remote_job_before_marking_terminal():
    requests = []

    def opener(request, timeout):
        requests.append((request.full_url, request.data, timeout))
        if request.full_url.endswith("/finish"):
            return Response({"status": "CANCELLED"})
        return Response()

    result = MODULE.execute(
        {"stepName": "on_cancel", "parameters": {"operationId": "operation-1"}},
        "http://storage", "token", opener=opener,
    )

    assert result == {"status": "CANCELLED"}
    assert requests[0][0].endswith("/operation-1/remote-job/stop")
    assert requests[1][0].endswith("/operation-1/finish")
    assert json.loads(requests[1][1])["errorCode"] == "STORAGE_TASK_CANCELLED"


def test_stop_failure_is_persisted_in_terminal_error_code():
    requests = []

    def opener(request, timeout):
        requests.append(request)
        if request.full_url.endswith("/stop"):
            raise OSError("remote unavailable")
        return Response({"status": "TIMED_OUT"})

    MODULE.execute(
        {"stepName": "on_timeout", "parameters": {"operationId": "operation-2"}},
        "http://storage", "token", opener=opener,
    )

    assert json.loads(requests[-1].data)["errorCode"] == "STORAGE_REMOTE_STOP_FAILED"
