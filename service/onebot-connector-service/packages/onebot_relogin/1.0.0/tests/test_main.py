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
    def __init__(self, ready_after=1, statuses=None):
        self.ready_after = ready_after
        self.probes = 0
        self.statuses = iter(statuses or ["REQUESTED"])
        self.last_status = "REQUESTED"

    def request_relogin(self, account_key, request_id, timeout=None):
        assert account_key == "qq_primary" and request_id == "request_1"
        assert timeout is None or timeout > 0
        self.last_status = next(self.statuses, self.last_status)
        result = {"status": self.last_status}
        if self.last_status != "ALREADY_ONLINE":
            result["requestedAt"] = "2026-08-25T10:00:00+00:00"
        return result

    def qr_ready(self, account_key, requested_at, timeout=None):
        assert account_key == "qq_primary"
        assert requested_at == "2026-08-25T10:00:00+00:00"
        assert timeout is None or timeout > 0
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


def test_execute_returns_explicit_already_online_result_without_qr_probe():
    client = FakeClient(statuses=["ALREADY_ONLINE"])
    result = MODULE.execute(
        FakeContext({"accountKey": "qq_primary", "requestId": "request_1"}), client)
    assert result == {"accountKey": "qq_primary", "requestId": "request_1",
                      "status": "ALREADY_ONLINE"}
    assert client.probes == 0


def test_execute_rechecks_durable_action_state_and_stops_after_failure():
    client = FakeClient(ready_after=999, statuses=["REQUESTED", "FAILED"])
    try:
        MODULE.execute(
            FakeContext({"accountKey": "qq_primary", "requestId": "request_1"}),
            client, sleeper=lambda _seconds: None)
    except RuntimeError as exception:
        assert "retry limit" in str(exception)
    else:
        raise AssertionError("exhausted relogin action must fail")


def test_qr_probe_retries_transient_gateway_and_unavailable_responses(monkeypatch):
    client = MODULE.ConnectorClient("http://127.0.0.1:23255", "token")
    for status in (502, 503):
        def unavailable(*_args, response_status=status, **_kwargs):
            raise HTTPError("http://127.0.0.1", response_status, "temporary", {}, None)
        monkeypatch.setattr(MODULE, "urlopen", unavailable)
        assert client.qr_ready("qq_primary", "2026-08-25T10:00:00+00:00") is False
