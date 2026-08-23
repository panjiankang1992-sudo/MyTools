import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_recover_move", SCRIPT)
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


def test_execute_retries_until_recovered():
    responses = iter([
        {"phase": "RECOVERING", "finished": False, "success": False},
        {"phase": "RECOVERED", "finished": True, "success": True},
    ])
    sleeps = []

    result = MODULE.execute("operation-id", "http://storage", "token",
                            opener=lambda _request, timeout: Response(next(responses)),
                            poll_seconds=0.1, sleeper=sleeps.append)

    assert result == {"operationId": "operation-id", "status": "RECOVERED"}
    assert sleeps == [0.1]
