import importlib.util
import json
from pathlib import Path
from uuid import uuid4

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_abort_native_copy", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, status="CANCELLED"):
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self):
        return json.dumps({"status": self.status}).encode()


def test_abort_deletes_target_before_setting_cancelled_terminal_state():
    operation_id = str(uuid4())
    methods = []

    def opener(request, timeout):
        methods.append(request.get_method())
        assert timeout in {30, 60}
        return Response()

    result = MODULE.execute({"stepName": "on_cancel", "parameters": {"operationId": operation_id}},
                            "http://storage", "token", opener)

    assert result == {"status": "CANCELLED"}
    assert methods == ["DELETE", "POST"]
