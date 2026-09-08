"""Asset Registry SDK fencing contract tests."""

import json
from unittest.mock import patch

from mytools_task_sdk.asset import AssetRegistryClient


class Response:
    """Minimal context-managed HTTP response."""

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        """Return one valid asset response."""
        return json.dumps({"id": "00000000-0000-4000-8000-000000000001", "version": 2}).encode()


def test_register_artifact_sends_execution_fence_headers():
    """Derived relation writes must carry the complete execution identity."""
    captured = {}

    def open_request(request, timeout):
        captured["request"] = request
        captured["timeout"] = timeout
        return Response()

    client = AssetRegistryClient("http://assets", "token")
    payload = {"idempotencyKey": "artifact-link:1"}
    context = {"taskInstanceId": "00000000-0000-4000-8000-000000000002",
               "stepName": "register_thumbnail", "fencingToken": 11}
    with patch("urllib.request.urlopen", open_request):
        client.register_artifact("00000000-0000-4000-8000-000000000003", payload, context)

    request = captured["request"]
    assert request.headers["X-task-business-key"] == "artifact-link:1"
    assert request.headers["X-task-fencing-token"] == "11"


def test_register_artifact_rejects_missing_execution_fence():
    """Incomplete task context must fail before any HTTP request is sent."""
    client = AssetRegistryClient("http://assets", "token")
    try:
        client.register_artifact("00000000-0000-4000-8000-000000000003",
                                 {"idempotencyKey": "artifact-link:2"}, {})
    except ValueError as exception:
        assert str(exception) == "Task execution fence context is missing"
    else:
        raise AssertionError("Missing execution fence was accepted")
