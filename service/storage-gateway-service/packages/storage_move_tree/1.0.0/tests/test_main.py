import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_move_tree", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, document):
        self.document = document

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self):
        return json.dumps(self.document).encode()


def test_execute_advances_until_success():
    responses = iter([
        {"phase": "COPYING", "finished": False, "success": False},
        {"phase": "VERIFYING", "finished": False, "success": False},
        {"phase": "TERMINAL", "finished": True, "success": True},
    ])
    sleeps = []

    result = MODULE.execute("operation-id", "http://storage", "token",
                            opener=lambda _request, timeout: Response(next(responses)),
                            poll_seconds=0.1, sleeper=sleeps.append)

    assert result == {"operationId": "operation-id", "status": "SUCCEEDED"}
    assert sleeps == [0.1, 0.1]


def test_execute_sends_execution_fence_headers():
    captured = {}

    def opener(request, timeout):
        captured.update(dict(request.header_items()))
        return Response({"phase": "TERMINAL", "finished": True, "success": True})

    context = {"taskInstanceId": "00000000-0000-4000-8000-000000000001",
               "stepName": "move", "fencingToken": 9}
    MODULE.execute("operation-id", "http://storage", "token", opener=opener, context=context)

    assert captured["X-task-instance-id"] == context["taskInstanceId"]
    assert captured["X-task-step-name"] == "move"
    assert captured["X-task-business-key"] == "operation-id"
    assert captured["X-task-fencing-token"] == "9"
