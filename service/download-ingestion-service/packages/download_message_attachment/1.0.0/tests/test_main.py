import importlib.util
import io
from pathlib import Path
import tempfile


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_message_attachment", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, content):
        self.stream = io.BytesIO(content)
        self.headers = {"Content-Length": str(len(content))}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.stream.close()

    def read(self, size):
        return self.stream.read(size)


def test_streams_by_opaque_job_id_without_provider_reference():
    captured = []
    parameters = {"downloadRequestId": "00000000-0000-4000-8000-000000000001",
                  "attachmentJobId": "00000000-0000-4000-8000-000000000002",
                  "itemId": "part-1", "fileName": "a.bin", "maxBytes": 1024}
    opener = lambda request, timeout: captured.append((request, timeout)) or Response(b"content")
    with tempfile.TemporaryDirectory() as directory:
        result = MODULE.stream_download(parameters, Path(directory), "http://messaging", "secret", opener)
        assert (Path(directory) / result["relativePath"]).read_bytes() == b"content"
    request = captured[0][0]
    assert request.full_url.endswith("/attachment-downloads/00000000-0000-4000-8000-000000000002/content")
    assert request.data == b""
    assert "provider" not in request.full_url and "secret" not in request.full_url


def test_rejects_stream_larger_than_limit():
    parameters = {"downloadRequestId": "00000000-0000-4000-8000-000000000001",
                  "attachmentJobId": "00000000-0000-4000-8000-000000000002",
                  "itemId": "part-1", "fileName": "a.bin", "maxBytes": 3}
    with tempfile.TemporaryDirectory() as directory:
        try:
            MODULE.stream_download(parameters, Path(directory), "http://messaging", "secret",
                                   lambda *_args, **_kwargs: Response(b"large"))
        except ValueError as exception:
            assert "exceeds" in str(exception)
        else:
            raise AssertionError("oversized attachment was accepted")
