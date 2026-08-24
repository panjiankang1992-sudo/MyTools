import importlib.util
import json
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_poll_email", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return b'{"accountKey":"primary_email","examinedCount":2,"acceptedCount":2,"lastUid":9}'


def test_sends_only_logical_account_key():
    captured = {}

    def opener(request, timeout):
        captured["payload"] = json.loads(request.data)
        captured["authorization"] = request.headers["Authorization"]
        captured["timeout"] = timeout
        return Response()

    result = MODULE.execute({"accountKey": "primary_email"}, "http://messaging", "secret", opener)

    assert captured["payload"] == {"accountKey": "primary_email"}
    assert captured["authorization"] == "Bearer secret"
    assert result["lastUid"] == 9
