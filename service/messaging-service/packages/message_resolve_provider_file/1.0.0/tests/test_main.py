import importlib.util
import json
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_resolve_provider_file", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps({"jobId": "00000000-0000-4000-8000-000000000001",
                           "status": "RESOLVED", "resolved": True}).encode()


@patch("urllib.request.urlopen", return_value=Response())
def test_only_sends_opaque_job_identifier(urlopen):
    result = MODULE.execute("00000000-0000-4000-8000-000000000001", "http://messaging", "secret")

    request = urlopen.call_args.args[0]
    assert request.data == b""
    assert "secret" not in request.full_url
    assert result == {"jobId": "00000000-0000-4000-8000-000000000001",
                      "status": "RESOLVED", "resolved": True}
