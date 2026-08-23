import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_abort_move", SCRIPT)
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


def test_execute_marks_recovery_after_bounded_compensation():
    urls = []

    def opener(request, timeout):
        urls.append(request.full_url)
        if request.full_url.endswith("/recovery-required"):
            return Response({"finished": True, "recoveryRequired": True})
        return Response({"finished": False, "recoveryRequired": False})

    result = MODULE.execute({"stepName": "on_timeout", "parameters": {"operationId": "id"}},
                            "http://storage", "token", opener, attempts=2,
                            poll_seconds=0, sleeper=lambda _seconds: None)

    assert result == {"status": "TIMED_OUT", "recoveryRequired": True}
    assert urls[-1].endswith("/recovery-required")
