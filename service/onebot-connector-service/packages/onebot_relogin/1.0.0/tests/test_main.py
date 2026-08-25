from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from urllib.error import HTTPError

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("onebot_relogin", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeContext:
    def __init__(self, parameters):
        self.parameters = parameters


class FakeClient:
    def __init__(self, ready_after=1):
        self.ready_after = ready_after
        self.probes = 0

    def request_relogin(self, account_key, request_id):
        assert account_key == "qq_primary" and request_id == "request_1"
        return {"requestedAt": "2026-08-25T10:00:00+00:00"}

    def qr_ready(self, account_key, requested_at):
        assert account_key == "qq_primary"
        assert requested_at == "2026-08-25T10:00:00+00:00"
        self.probes += 1
        return self.probes >= self.ready_after


def test_execute_waits_for_fresh_qr_without_returning_bytes_or_path():
    result = MODULE.execute(FakeContext({"accountKey": "qq_primary", "requestId": "request_1"}),
                            FakeClient(ready_after=2), sleeper=lambda _seconds: None)
    assert result == {"accountKey": "qq_primary", "requestId": "request_1",
                      "requestedAt": "2026-08-25T10:00:00+00:00", "status": "QR_READY"}


def test_execute_rejects_unbounded_identifiers():
    try:
        MODULE.execute(FakeContext({"accountKey": "../unsafe", "requestId": "request_1"}),
                       FakeClient(), sleeper=lambda _seconds: None)
    except ValueError:
        pass
    else:
        raise AssertionError("unsafe account key must be rejected")


def test_qr_probe_retries_transient_gateway_and_unavailable_responses(monkeypatch):
    client = MODULE.ConnectorClient("http://127.0.0.1:23255", "token")
    for status in (502, 503):
        def unavailable(*_args, response_status=status, **_kwargs):
            raise HTTPError("http://127.0.0.1", response_status, "temporary", {}, None)
        monkeypatch.setattr(MODULE, "urlopen", unavailable)
        assert client.qr_ready("qq_primary", "2026-08-25T10:00:00+00:00") is False
