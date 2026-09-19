import asyncio

import pytest

import mytools_qq_connector.main as connector_main
from mytools_qq_connector.outbound_wal import OutboundWalDeferredError
from test_client import CONFIG


def test_main_constructs_and_supervises_inbound_worker(monkeypatch, tmp_path):
    created = {}

    class FakeConfig:
        @staticmethod
        def load():
            return CONFIG

    class FakeLoginWal:
        def __init__(self, path, **settings):
            created["login_path"] = path
            created["login_settings"] = settings

        def redrive(self, event_key):
            created["login_redrive"] = event_key

        def acknowledge_dead(self, event_key):
            created["login_ack"] = event_key

    class FakeInboundWal:
        def __init__(self, path, **settings):
            created["inbound_path"] = path
            created["inbound_settings"] = settings

        def redrive(self, event_key):
            created["redriven_event_key"] = event_key
            return type("Entry", (), {"event_key": event_key})()

    class FakeOutboundWal:
        def __init__(self, path, **settings):
            created["outbound_path"] = path
            created["outbound_settings"] = settings
            created["outbound_wal"] = self
            self.dead = 0

        def stats(self):
            return {"pending": 0, "claimed": 0, "done": 0, "dead": self.dead}

        def redrive(self, event_key):
            created["outbound_redrive"] = event_key
            return {"sender": "target", "messageId": "message", "text": "done",
                    "idempotencyKey": "automation-completion-run-page-1"}

    class FakeSession:
        async def close(self):
            created["session_closed"] = True

    class FakeConnector:
        def __init__(self, config, session, login_wal, inbound_wal):
            created["connector_dependencies"] = (login_wal, inbound_wal)
            self._inbound_wakeup = asyncio.Event()
            self._login_wakeup = asyncio.Event()
            created["connector"] = self

        async def run(self, stop):
            created["gateway_started"] = True
            await asyncio.Event().wait()

        async def run_login_worker(self, stop):
            created["login_worker_started"] = True
            await asyncio.Event().wait()

        async def run_inbound_worker(self, stop):
            created["inbound_worker_started"] = True
            await asyncio.sleep(0)
            raise RuntimeError("inbound worker stopped")

        async def command_health(self):
            return {"status": "UP"}

    class FakeRouter:
        def add_get(self, path, handler):
            created.setdefault("get_handlers", {})[path] = handler
            return None

        def add_post(self, path, handler):
            created.setdefault("post_handlers", {})[path] = handler
            return None

    class FakeApplication:
        def __init__(self, **kwargs):
            self.router = FakeRouter()

    class FakeRunner:
        def __init__(self, application):
            pass

        async def setup(self):
            return None

        async def cleanup(self):
            created["runner_cleaned"] = True

    class FakeSite:
        def __init__(self, runner, host, port):
            pass

        async def start(self):
            return None

    monkeypatch.setattr(connector_main, "Config", FakeConfig)
    monkeypatch.setattr(connector_main, "LoginCommandWal", FakeLoginWal)
    monkeypatch.setattr(connector_main, "InboundEventWal", FakeInboundWal)
    monkeypatch.setattr(connector_main, "OutboundTextWal", FakeOutboundWal)
    monkeypatch.setattr(connector_main, "QQConnector", FakeConnector)
    monkeypatch.setattr(connector_main.aiohttp, "ClientSession",
                        lambda **kwargs: FakeSession())
    monkeypatch.setattr(connector_main.web, "Application", FakeApplication)
    monkeypatch.setattr(connector_main.web, "AppRunner", FakeRunner)
    monkeypatch.setattr(connector_main.web, "TCPSite", FakeSite)

    async def scenario():
        with pytest.raises(RuntimeError, match="inbound worker stopped"):
            await connector_main.run()

    asyncio.run(scenario())

    assert created["gateway_started"] is True
    assert created["login_worker_started"] is True
    assert created["inbound_worker_started"] is True
    assert created["inbound_settings"]["max_pending_records"] \
        == CONFIG.inbound_max_pending_records
    assert created["login_settings"] == {
        "max_done_records": 10_000, "max_dead_records": 1_000}
    assert created["session_closed"] is True
    assert created["runner_cleaned"] is True

    redrive = created["post_handlers"][
        "/internal/v1/inbound-wal/{eventKey}/redrive"]
    event_key = "a" * 64

    class FakeRequest:
        headers = {"Authorization": f"Bearer {CONFIG.automation_token}"}
        match_info = {"eventKey": event_key}

    response = asyncio.run(redrive(FakeRequest()))
    assert response.status == 202
    assert created["redriven_event_key"] == event_key
    assert created["connector"]._inbound_wakeup.is_set()

    class UnauthorizedRequest:
        headers = {"Authorization": "Bearer invalid"}
        match_info = {"eventKey": event_key}

    with pytest.raises(connector_main.web.HTTPUnauthorized):
        asyncio.run(redrive(UnauthorizedRequest()))

    login_redrive = created["post_handlers"][
        "/internal/v1/login-wal/{eventKey}/redrive"]
    login_ack = created["post_handlers"][
        "/internal/v1/login-wal/{eventKey}/ack"]
    assert asyncio.run(login_redrive(FakeRequest())).status == 202
    assert created["login_redrive"] == event_key
    assert asyncio.run(login_ack(FakeRequest())).status == 200
    assert created["login_ack"] == event_key
    with pytest.raises(connector_main.web.HTTPUnauthorized):
        asyncio.run(login_redrive(UnauthorizedRequest()))
    with pytest.raises(connector_main.web.HTTPUnauthorized):
        asyncio.run(login_ack(UnauthorizedRequest()))

    class SendRequest(FakeRequest):
        async def json(self):
            return {"sender": "target", "messageId": "message", "text": "done",
                    "idempotencyKey": "automation-completion-run-page-1"}

    async def defer_delivery(_connector, _payload, _outbound_wal):
        raise OutboundWalDeferredError(17)

    monkeypatch.setattr(connector_main, "deliver_text", defer_delivery)
    send_text = created["post_handlers"]["/internal/v1/messages/text"]
    deferred = asyncio.run(send_text(SendRequest()))
    assert deferred.status == 425
    assert deferred.headers["Retry-After"] == "17"

    outbound_redrive = created["post_handlers"][
        "/internal/v1/outbound-wal/{eventKey}/redrive"]
    deferred_redrive = asyncio.run(outbound_redrive(FakeRequest()))
    assert deferred_redrive.status == 425
    assert deferred_redrive.headers["Retry-After"] == "17"
    assert created["outbound_redrive"] == event_key

    health = created["get_handlers"]["/health"]
    created["outbound_wal"].dead = 1
    assert asyncio.run(health(FakeRequest())).status == 503


@pytest.mark.parametrize("raw", ["0", "-1", "invalid"])
def test_login_terminal_limit_environment_fails_fast(monkeypatch, raw):
    monkeypatch.setenv("QQ_CONNECTOR_LOGIN_DONE_MAX_RECORDS", raw)
    with pytest.raises(ValueError, match="positive"):
        connector_main.positive_env("QQ_CONNECTOR_LOGIN_DONE_MAX_RECORDS", 10_000)
