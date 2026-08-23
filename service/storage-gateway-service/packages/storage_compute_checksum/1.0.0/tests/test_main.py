import hashlib
import importlib.util
import io
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_compute_checksum", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, content):
        self.stream = io.BytesIO(content)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, size=-1):
        return self.stream.read(size)


def test_execute_streams_content_and_reconciles_digest():
    content = b"mount-affine-content"
    requests = []

    def opener(request, timeout):
        requests.append((request, timeout))
        if request.full_url.endswith("/content"):
            return Response(content)
        body = json.loads(request.data)
        assert body["sizeBytes"] == len(content)
        assert body["contentSha256"] == hashlib.sha256(content).hexdigest()
        return Response(b'{"status":"SUCCEEDED"}')

    result = MODULE.execute("operation-id", "http://storage", "token", opener)

    assert result["status"] == "SUCCEEDED"
    assert result["sizeBytes"] == len(content)
    assert len(requests) == 2
