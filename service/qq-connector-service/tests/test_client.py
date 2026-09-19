import asyncio
from dataclasses import replace
from datetime import UTC, datetime
import hashlib
import json
import time
from unittest.mock import patch

import pytest

from mytools_qq_connector.client import (
    InboundWalUnavailableError,
    InternalHttpError,
    PermanentLoginCommandError,
    QQConnector,
)
from mytools_qq_connector.config import Config
from mytools_qq_connector.inbound_wal import (
    GatewayCheckpoint,
    InboundEvent,
    InboundEventWal,
)
from mytools_qq_connector.login_wal import LoginCommand, LoginCommandWal
from mytools_qq_connector.main import deliver_text, reply_sequence
from mytools_qq_connector.outbound_wal import (
    OutboundTextWal,
    OutboundWalConflictError,
)


CONFIG = Config("qq_main", "app", "secret", 55, "allowed", "https://api.example.test",
                "https://token.example.test", "wss://gateway.example.test", 1,
                "http://messaging", "messaging-token", "http://scheduler",
                "scheduler-token", "http://onebot", "onebot-token", "qq-napcat", "automation-token")


def test_automation_replies_use_distinct_qq_sequences():
    assert reply_sequence("automation-start-run", 2) == 1
    assert reply_sequence("automation-progress-run-action-25", 3) == 2
    assert reply_sequence("automation-duplicate-link-digest", 3) == 2
    assert reply_sequence("automation-completion-run", 2) == 3
    assert reply_sequence("automation-completion-run-page-1", 2) == 3
    assert reply_sequence("automation-completion-run-page-2", 2) == 4
    assert reply_sequence("automation-completion-run-page-8", 2) == 10
    with pytest.raises(ValueError):
        reply_sequence("automation-completion-run-page-9", 2)
    assert reply_sequence("run-id", 2) == 2
    assert reply_sequence("", 2) == 2


def test_outbound_wal_suppresses_completed_upper_layer_retry(tmp_path):
    async def scenario():
        connector = FakeConnector()
        wal = OutboundTextWal(str(tmp_path / "outbound"))
        payload = {
            "sender": "allowed", "messageId": "message-current", "text": "done",
            "eventType": "C2C_MESSAGE_CREATE",
            "idempotencyKey": "automation-completion-run-page-1",
        }
        await deliver_text(connector, payload, wal)
        await deliver_text(connector, payload, wal)
        assert connector.replies == [
            ("allowed", "message-current", "done", False)]
        assert wal.stats() == {"pending": 0, "claimed": 0, "done": 1, "dead": 0}

    asyncio.run(scenario())


def test_outbound_wal_rejects_same_key_with_changed_payload(tmp_path):
    async def scenario():
        connector = FakeConnector()
        wal = OutboundTextWal(str(tmp_path / "outbound"))
        payload = {
            "sender": "allowed", "messageId": "message-current", "text": "done",
            "eventType": "C2C_MESSAGE_CREATE",
            "idempotencyKey": "automation-completion-run-page-1",
        }
        await deliver_text(connector, payload, wal)
        with pytest.raises(OutboundWalConflictError):
            await deliver_text(connector, {**payload, "text": "changed"}, wal)

    asyncio.run(scenario())


def test_outbound_delivery_timeout_returns_before_upstream_deadline(tmp_path):
    class SlowConnector(FakeConnector):
        async def send_text(self, sender, message_id, text, sequence=1,
                            event_type="C2C_MESSAGE_CREATE", active_only=False):
            await asyncio.Event().wait()

    async def scenario():
        wal = OutboundTextWal(str(tmp_path / "outbound"))
        payload = {
            "sender": "allowed", "messageId": "message-current", "text": "done",
            "eventType": "C2C_MESSAGE_CREATE",
            "idempotencyKey": "automation-completion-run-page-1",
        }
        with patch("mytools_qq_connector.main.OUTBOUND_DELIVERY_TIMEOUT_SECONDS", 0.01), \
                pytest.raises(TimeoutError):
            await deliver_text(SlowConnector(), payload, wal)
        assert wal.stats() == {"pending": 1, "claimed": 0, "done": 0, "dead": 0}

    asyncio.run(scenario())


def test_qq_request_refreshes_cached_token_once_after_401():
    class FakeResponse:
        def __init__(self, status, body):
            self.status = status
            self.body = body

        async def __aenter__(self):
            return self

        async def __aexit__(self, exception_type, exception, traceback):
            return False

        async def json(self, content_type=None):
            if isinstance(self.body, Exception):
                raise self.body
            return self.body

    class FakeSession:
        def __init__(self):
            self.token_requests = 0
            self.authorizations = []
            self.responses = [
                FakeResponse(401, ValueError("invalid error body")),
                FakeResponse(200, {"id": "sent"}),
            ]

        def post(self, url, **kwargs):
            self.token_requests += 1
            return FakeResponse(200, {
                "access_token": "fresh-token", "expires_in": 7200})

        def request(self, method, url, **kwargs):
            self.authorizations.append(kwargs["headers"]["Authorization"])
            return self.responses.pop(0)

    async def scenario():
        session = FakeSession()
        connector = QQConnector(
            CONFIG, session, FakeLoginWal(), FakeInboundWal())
        connector._token = "stale-token"
        connector._token_expires_at = time.monotonic() + 7200

        assert await connector._qq_request(
            "POST", "/v2/users/target/messages", {"content": "done"}) == {
                "id": "sent"}
        assert session.authorizations == ["QQBot stale-token", "QQBot fresh-token"]
        assert session.token_requests == 1
        assert connector._token == "fresh-token"

    asyncio.run(scenario())


def test_qq_request_does_not_retry_more_than_once_after_repeated_401():
    class FakeResponse:
        status = 401

        async def __aenter__(self):
            return self

        async def __aexit__(self, exception_type, exception, traceback):
            return False

        async def json(self, content_type=None):
            return {"code": 112, "message": "unauthorized"}

    class FakeSession:
        def __init__(self):
            self.token_requests = 0
            self.request_count = 0

        def post(self, url, **kwargs):
            self.token_requests += 1
            response = FakeResponse()
            response.status = 200
            response.json = self.token_body
            return response

        async def token_body(self, content_type=None):
            return {"access_token": "fresh-token", "expires_in": 7200}

        def request(self, method, url, **kwargs):
            self.request_count += 1
            return FakeResponse()

    async def scenario():
        session = FakeSession()
        connector = QQConnector(
            CONFIG, session, FakeLoginWal(), FakeInboundWal())
        connector._token = "stale-token"
        connector._token_expires_at = time.monotonic() + 7200

        with pytest.raises(RuntimeError, match="HTTP 401"):
            await connector._qq_request(
                "POST", "/v2/users/target/messages", {"content": "done"})
        assert session.request_count == 2
        assert session.token_requests == 1
        assert connector._token == ""

    asyncio.run(scenario())


def test_config_load_requires_non_blank_allowed_sender(monkeypatch):
    required = {
        "QQ_CONNECTOR_APP_ID": "app",
        "QQ_CONNECTOR_APP_SECRET": "secret",
        "QQ_CONNECTOR_OWNER_ID": "55",
        "MESSAGING_INTERNAL_TOKEN": "messaging-token",
        "ONEBOT_CONNECTOR_INTERNAL_TOKEN": "onebot-token",
        "MESSAGE_AUTOMATION_INTERNAL_TOKEN": "automation-token",
    }
    for key, value in required.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setenv("QQ_CONNECTOR_ALLOWED_SENDER", "   ")
    with pytest.raises(ValueError, match="allowed sender"):
        Config.load()

    monkeypatch.setenv("QQ_CONNECTOR_ALLOWED_SENDER", "  allowed  ")
    assert Config.load().allowed_sender == "allowed"


class FakeLoginWal:
    """仅用于验证 Gateway 入站顺序的内存 WAL。"""

    def __init__(self, actions=None):
        self.actions = actions if actions is not None else []
        self.commands = {}

    def store(self, command):
        """按消息键保存一条待处理命令。"""
        self.actions.append("wal")
        self.commands.setdefault(command.message_id, command)
        return True

    def stats(self):
        """返回内存 WAL 的安全计数。"""
        return {"pending": len(self.commands), "done": 0, "dead": 0}

    def rejected_count(self):
        """返回永久拒绝事件数。"""
        return 0


class FakeInboundWal:
    """记录 Gateway 持久化顺序的内存 WAL。"""

    def __init__(self, actions=None):
        self.actions = actions if actions is not None else []
        self.checkpoint = None
        self.events = {}

    def load_checkpoint(self):
        """返回当前内存 checkpoint。"""
        return self.checkpoint

    def create_event(self, account_key, session_id, sequence, payload):
        """创建稳定幂等的内存事件。"""
        return InboundEvent.create(account_key, session_id, sequence, payload)

    def store(self, inbound_event):
        """先记录原始事件落盘动作。"""
        self.actions.append("inbound-wal")
        self.events.setdefault(inbound_event.event_key, inbound_event)
        return True

    def save_checkpoint(self, session_id, sequence, account_key=None):
        """记录 checkpoint 必须晚于原始事件。"""
        self.actions.append("checkpoint")
        revision = 0 if self.checkpoint is None else self.checkpoint.revision + 1
        self.checkpoint = GatewayCheckpoint(
            session_id, sequence, account_key, time.time(), revision)
        return self.checkpoint

    def invalidate_checkpoint(self, session_id=None):
        """删除匹配的内存 checkpoint。"""
        if self.checkpoint is None:
            return False
        if session_id is not None and self.checkpoint.session_id != session_id:
            return False
        self.actions.append("checkpoint-invalidated")
        self.checkpoint = None
        return True

    def claim_batch(self, now, limit, lease_seconds=60, max_attempts=9):
        """内存桩不自动运行 worker。"""
        return []

    def stats(self):
        """返回不包含正文的内存队列计数。"""
        return {"pending": len(self.events), "processing": 0,
                "done": 0, "dead": 0, "payloadBytes": 0}


class FakeConnector(QQConnector):
    def __init__(self, config=CONFIG, login_wal=None, inbound_wal=None):
        self.actions = []
        self.fake_login_wal = login_wal or FakeLoginWal(self.actions)
        self.fake_inbound_wal = inbound_wal or FakeInboundWal(self.actions)
        super().__init__(config, object(), self.fake_login_wal,
                         self.fake_inbound_wal)
        self.received = []
        self.replies = []

    @property
    def commands(self):
        """返回已持久化登录命令的稳定业务键。"""
        return [(command.sender_id, command.message_id)
                for command in self.fake_login_wal.commands.values()]

    async def _messaging_receive(self, payload, sender, message_id, content, target=None,
                                 conversation_type="c2c"):
        self.actions.append("persist")
        self.received.append((payload["t"], sender, message_id, content))
        return content or "[empty]"

    async def send_text(self, sender, message_id, text, sequence=1,
                        event_type="C2C_MESSAGE_CREATE", active_only=False,
                        idempotency_key=""):
        self.actions.append("reply")
        self.replies.append((sender, message_id, text, active_only))


class CapturingConnector(QQConnector):
    def __init__(self, config=CONFIG):
        super().__init__(config, object(), FakeLoginWal(), FakeInboundWal())
        self.request = None

    async def _internal_json(self, method, url, payload, token):
        self.request = (method, url, payload, token)
        return {"id": "inbound-message"}


def event(content="登录", sender="allowed", message_id="message-1"):
    return {"t": "C2C_MESSAGE_CREATE", "d": {"id": message_id, "content": content,
            "author": {"user_openid": sender}}}


def test_exact_authorized_login_command_is_persisted_before_acknowledgement():
    async def scenario():
        connector = FakeConnector()
        await connector.receive(event())
        assert connector.received == []
        assert connector.commands == [("allowed", "message-1")]
        assert connector.replies == [
            ("allowed", "message-1", "正在生成登录二维码，请稍候。", False)]
        assert connector.actions == ["wal", "reply"]
    asyncio.run(scenario())


def test_login_worker_sends_sanitized_terminal_reply(tmp_path):
    class FailingReloginConnector(FakeConnector):
        async def _create_relogin_task(self, message_id, _generation):
            raise PermanentLoginCommandError("sensitive internal failure")

    async def scenario():
        config = replace(CONFIG, login_worker_poll_seconds=0.01,
                         login_retry_max_seconds=0.01)
        wal = LoginCommandWal(tmp_path / "login-wal")
        connector = FailingReloginConnector(config, wal)
        await connector.receive(event())
        stop = asyncio.Event()
        worker = asyncio.create_task(connector.run_login_worker(stop))
        for _ in range(100):
            if wal.stats()["dead"] == 1:
                break
            await asyncio.sleep(0.01)
        stop.set()
        await asyncio.wait_for(worker, timeout=1)
        assert connector.replies == [
            ("allowed", "message-1", "正在生成登录二维码，请稍候。", False),
            ("allowed", "message-1", "QQ 登录二维码生成失败，请稍后重试。", False),
        ]
        assert all("sensitive" not in reply[2] for reply in connector.replies)
        assert wal.stats()["pending"] == 0
        assert wal.stats()["done"] == 0
        assert wal.stats()["dead"] == 1
    asyncio.run(scenario())


def test_group_message_is_persisted_for_unified_acknowledgement():
    async def scenario():
        connector = FakeConnector()
        payload = event(content="https://example.test/group", message_id="group-message")
        payload["t"] = "GROUP_AT_MESSAGE_CREATE"
        payload["d"]["group_openid"] = "group-target"
        await connector.receive(payload)
        assert connector.received == [("GROUP_AT_MESSAGE_CREATE", "allowed",
                                       "group-message", "https://example.test/group")]
        assert connector.replies == []
    asyncio.run(scenario())


def test_group_login_command_returns_private_chat_guidance_without_persistence():
    async def scenario():
        connector = FakeConnector()
        payload = event(content="登录", message_id="group-login-message")
        payload["t"] = "GROUP_AT_MESSAGE_CREATE"
        payload["d"]["group_openid"] = "group-target"
        await connector.receive(payload)
        assert connector.received == []
        assert connector.commands == []
        assert connector.replies == [
            ("group-target", "group-login-message",
             "登录命令仅支持私聊，请私聊机器人发送“登录”。", False),
        ]
        assert connector.actions == ["reply"]
    asyncio.run(scenario())


def test_non_exact_or_other_sender_message_is_persisted_without_command():
    async def scenario():
        connector = FakeConnector()
        await connector.receive(event(content="请登录"))
        await connector.receive(event(content="登录", sender="other", message_id="message-2"))
        await asyncio.sleep(0)
        assert connector.received == [
            ("C2C_MESSAGE_CREATE", "allowed", "message-1", "请登录"),
            ("C2C_MESSAGE_CREATE", "other", "message-2", "登录")]
        assert connector.commands == []
    asyncio.run(scenario())


def test_gateway_sequence_advances_only_after_message_persistence_succeeds():
    class FailingInboundWal(FakeInboundWal):
        def store(self, inbound_event):
            self.actions.append("inbound-wal")
            raise OSError("persistence unavailable")

    async def scenario():
        connector = FakeConnector()
        connector._session_id = "session"
        connector._sequence = 40
        payload = event(content="ordinary", message_id="message-success")
        payload["s"] = 41
        await connector._handle_dispatch(payload)
        assert connector._sequence == 41
        assert connector.received == []
        assert connector.actions == ["inbound-wal", "checkpoint"]

        failed_actions = []
        failing = FakeConnector(
            inbound_wal=FailingInboundWal(failed_actions))
        failing._session_id = "session"
        failing._sequence = 41
        payload = event(content="ordinary", message_id="message-failed")
        payload["s"] = 42
        with pytest.raises(InboundWalUnavailableError,
                           match="event persistence failed"):
            await failing._handle_dispatch(payload)
        assert failing._sequence == 41
        assert failed_actions == ["inbound-wal"]
    asyncio.run(scenario())


def test_gateway_run_exits_on_inbound_wal_failure():
    class FailingConnector(FakeConnector):
        async def connected(self, stop):
            raise InboundWalUnavailableError("disk unavailable")

    async def scenario():
        connector = FailingConnector()
        with pytest.raises(InboundWalUnavailableError, match="disk unavailable"):
            await asyncio.wait_for(connector.run(asyncio.Event()), timeout=0.2)

    asyncio.run(scenario())


def test_gateway_connection_rebuilds_when_heartbeat_sender_fails():
    class FakeWebSocket:
        def __init__(self):
            self.closed = False
            self.sent = []

        async def receive_json(self, timeout):
            return {"op": 10, "d": {"heartbeat_interval": 1}}

        async def send_json(self, payload):
            self.sent.append(payload)
            if payload["op"] == 1:
                raise RuntimeError("heartbeat send failed")

        def __aiter__(self):
            async def messages():
                await asyncio.Event().wait()
                yield None
            return messages()

    class WebSocketContext:
        def __init__(self, websocket):
            self.websocket = websocket

        async def __aenter__(self):
            return self.websocket

        async def __aexit__(self, exception_type, exception, traceback):
            self.websocket.closed = True

    class FakeSession:
        def __init__(self, websocket):
            self.websocket = websocket

        def ws_connect(self, url, **kwargs):
            return WebSocketContext(self.websocket)

    class GatewayConnector(FakeConnector):
        async def access_token(self):
            return "token"

        async def gateway_url(self):
            return "wss://gateway.example.test"

    async def scenario():
        websocket = FakeWebSocket()
        connector = GatewayConnector()
        connector.session = FakeSession(websocket)

        with pytest.raises(RuntimeError, match="heartbeat send failed"):
            await asyncio.wait_for(connector.connected(asyncio.Event()), timeout=0.2)

        assert websocket.sent[0]["op"] == 2
        assert any(payload["op"] == 1 for payload in websocket.sent)

    asyncio.run(scenario())


def test_gateway_connection_rebuilds_when_heartbeat_has_no_server_activity():
    class FakeWebSocket:
        def __init__(self):
            self.closed = False
            self.sent = []

        async def receive_json(self, timeout):
            return {"op": 10, "d": {"heartbeat_interval": 1}}

        async def send_json(self, payload):
            self.sent.append(payload)

        def __aiter__(self):
            async def messages():
                await asyncio.Event().wait()
                yield None
            return messages()

    class WebSocketContext:
        def __init__(self, websocket):
            self.websocket = websocket

        async def __aenter__(self):
            return self.websocket

        async def __aexit__(self, exception_type, exception, traceback):
            self.websocket.closed = True

    class FakeSession:
        def __init__(self, websocket):
            self.websocket = websocket

        def ws_connect(self, url, **kwargs):
            return WebSocketContext(self.websocket)

    class GatewayConnector(FakeConnector):
        async def access_token(self):
            return "token"

        async def gateway_url(self):
            return "wss://gateway.example.test"

    async def scenario():
        websocket = FakeWebSocket()
        connector = GatewayConnector()
        connector.session = FakeSession(websocket)

        with pytest.raises(
                RuntimeError, match="heartbeat acknowledgement timed out"):
            await asyncio.wait_for(connector.connected(asyncio.Event()), timeout=0.2)

        assert sum(payload["op"] == 1 for payload in websocket.sent) >= 1

    asyncio.run(scenario())


def test_login_wal_failure_is_retried_by_worker_after_gateway_checkpoint(tmp_path):
    class FailingLoginWal(FakeLoginWal):
        def store(self, command):
            raise OSError("disk unavailable")

    async def scenario():
        inbound_wal = InboundEventWal(tmp_path / "inbound-wal")
        connector = FakeConnector(login_wal=FailingLoginWal(),
                                  inbound_wal=inbound_wal)
        connector._session_id = "session"
        connector._sequence = 50
        payload = event()
        payload["s"] = 51

        await connector._handle_dispatch(payload)
        entry = inbound_wal.claim_batch(time.time(), 1)[0]
        completed = await connector._process_inbound_entry(entry)

        assert connector._sequence == 51
        assert completed is False
        assert inbound_wal.stats()["pending"] == 1
        assert connector.replies == []

    asyncio.run(scenario())


def test_gateway_ready_and_resumed_advance_sequence():
    async def scenario():
        connector = FakeConnector()
        await connector._handle_dispatch({"t": "READY", "s": 7,
                                          "d": {"session_id": "session"}})
        assert connector._session_id == "session"
        assert connector._sequence == 7
        await connector._handle_dispatch({"t": "RESUMED", "s": 8, "d": {}})
        assert connector._sequence == 8
    asyncio.run(scenario())


def test_permanent_bad_message_is_audited_and_does_not_block_next_message(tmp_path):
    class PoisonAwareConnector(FakeConnector):
        async def _messaging_receive(self, payload, sender, message_id, content, target=None,
                                     conversation_type="c2c"):
            if content == "poison":
                raise ValueError("nested input is too large")
            return await super()._messaging_receive(
                payload, sender, message_id, content, target, conversation_type)

    async def scenario():
        login_path = tmp_path / "login-wal"
        inbound_path = tmp_path / "inbound-wal"
        login_wal = LoginCommandWal(login_path)
        inbound_wal = InboundEventWal(inbound_path)
        connector = PoisonAwareConnector(CONFIG, login_wal, inbound_wal)
        connector._session_id = "session"
        connector._sequence = 60
        poison = event(content="poison", message_id="poison-message")
        poison["s"] = 61

        await connector._handle_dispatch(poison)

        assert connector._sequence == 61
        healthy = event(content="normal", message_id="healthy-message")
        healthy["s"] = 62
        await connector._handle_dispatch(healthy)
        assert connector._sequence == 62
        assert connector.received == []

        entries = inbound_wal.claim_batch(time.time(), 2)
        await asyncio.gather(
            *(connector._process_inbound_entry(entry) for entry in entries))

        assert login_wal.rejected_count() == 1
        assert connector.received == [
            ("C2C_MESSAGE_CREATE", "allowed", "healthy-message", "normal")]
        assert inbound_wal.stats()["dead"] == 1
        assert inbound_wal.stats()["done"] == 1

        # 重启后只恢复 checkpoint 和未完成记录，不会重新执行 DEAD 毒消息。
        restarted = PoisonAwareConnector(
            CONFIG, LoginCommandWal(login_path), InboundEventWal(inbound_path))
        assert restarted._session_id == "session"
        assert restarted._sequence == 62
        assert restarted.inbound_wal.claim_batch(time.time(), 2) == []
        assert restarted.login_wal.rejected_count() == 1

    asyncio.run(scenario())


def test_only_payload_4xx_is_dead_while_service_failures_retry_without_blocking(tmp_path):
    class FailingMessagingConnector(FakeConnector):
        def __init__(self, status, login_wal, inbound_wal):
            super().__init__(CONFIG, login_wal, inbound_wal)
            self.status = status

        async def _messaging_receive(self, payload, sender, message_id, content, target=None,
                                     conversation_type="c2c"):
            raise InternalHttpError(self.status)

    async def scenario():
        rejected_wal = LoginCommandWal(tmp_path / "rejected-wal")
        rejected_inbound = InboundEventWal(tmp_path / "rejected-inbound")
        rejected = FailingMessagingConnector(400, rejected_wal, rejected_inbound)
        rejected._session_id = "session"
        rejected._sequence = 70
        bad_request = event(content="ordinary", message_id="bad-request")
        bad_request["s"] = 71
        await rejected._handle_dispatch(bad_request)
        rejected_entry = rejected_inbound.claim_batch(time.time(), 1)[0]
        assert await rejected._process_inbound_entry(rejected_entry) is True
        assert rejected._sequence == 71
        assert rejected_wal.rejected_count() == 1
        assert rejected_inbound.stats()["dead"] == 1

        for status in (401, 403, 404, 409, 503):
            retry_wal = LoginCommandWal(tmp_path / f"retry-wal-{status}")
            retry_inbound = InboundEventWal(tmp_path / f"retry-inbound-{status}")
            retrying = FailingMessagingConnector(status, retry_wal, retry_inbound)
            retrying._session_id = "session"
            retrying._sequence = 80
            unavailable = event(content="ordinary", message_id=f"unavailable-{status}")
            unavailable["s"] = 81
            await retrying._handle_dispatch(unavailable)
            retry_entry = retry_inbound.claim_batch(time.time(), 1)[0]
            assert await retrying._process_inbound_entry(retry_entry) is False
            assert retrying._sequence == 81
            assert retry_wal.rejected_count() == 0
            assert retry_inbound.stats()["pending"] == 1

    asyncio.run(scenario())


def test_inbound_wal_recovers_frame_and_checkpoint_after_restart(tmp_path):
    async def scenario():
        config = replace(CONFIG, inbound_worker_poll_seconds=0.01)
        login_wal = LoginCommandWal(tmp_path / "login-wal")
        inbound_path = tmp_path / "inbound-wal"
        first = FakeConnector(config, login_wal, InboundEventWal(inbound_path))
        await first._handle_dispatch({"t": "READY", "s": 7,
                                      "d": {"session_id": "session-restart"}})
        payload = event(content="after-crash", message_id="restart-message")
        payload["s"] = 8
        await first._handle_dispatch(payload)
        assert first.received == []
        assert first.inbound_wal.stats()["pending"] == 1

        recovered = FakeConnector(
            config, login_wal, InboundEventWal(inbound_path))
        assert recovered._session_id == "session-restart"
        assert recovered._sequence == 8
        stop = asyncio.Event()
        worker = asyncio.create_task(recovered.run_inbound_worker(stop))
        for _ in range(100):
            if recovered.inbound_wal.stats()["done"] == 1:
                break
            await asyncio.sleep(0.01)
        stop.set()
        await asyncio.wait_for(worker, timeout=1)

        assert recovered.received == [
            ("C2C_MESSAGE_CREATE", "allowed", "restart-message", "after-crash")]
        assert recovered.inbound_wal.stats()["pending"] == 0
        assert recovered.inbound_wal.stats()["done"] == 1

    asyncio.run(scenario())


def test_transient_messaging_failure_does_not_block_later_wal_event(tmp_path):
    class FlakyConnector(FakeConnector):
        def __init__(self, config, login_wal, inbound_wal):
            super().__init__(config, login_wal, inbound_wal)
            self.attempted = []

        async def _messaging_receive(self, payload, sender, message_id, content, target=None,
                                     conversation_type="c2c"):
            self.attempted.append(message_id)
            if message_id == "slow-message" \
                    and self.attempted.count(message_id) == 1:
                raise InternalHttpError(503)
            return await super()._messaging_receive(
                payload, sender, message_id, content, target, conversation_type)

    async def scenario():
        config = replace(CONFIG, inbound_worker_poll_seconds=0.01,
                         inbound_retry_max_seconds=0.01)
        inbound_wal = InboundEventWal(tmp_path / "inbound-wal")
        connector = FlakyConnector(
            config, LoginCommandWal(tmp_path / "login-wal"), inbound_wal)
        await connector._handle_dispatch({"t": "READY", "s": 10,
                                          "d": {"session_id": "session"}})
        slow = event(content="slow", message_id="slow-message")
        slow["s"] = 11
        fast = event(content="fast", message_id="fast-message")
        fast["s"] = 12
        await connector._handle_dispatch(slow)
        await connector._handle_dispatch(fast)

        stop = asyncio.Event()
        worker = asyncio.create_task(connector.run_inbound_worker(stop))
        for _ in range(200):
            if inbound_wal.stats()["done"] == 2:
                break
            await asyncio.sleep(0.01)
        stop.set()
        await asyncio.wait_for(worker, timeout=1)

        assert connector._sequence == 12
        assert connector.attempted[:2] == ["slow-message", "fast-message"]
        assert connector.attempted.count("slow-message") == 2
        assert inbound_wal.stats()["done"] == 2
        assert inbound_wal.stats()["dead"] == 0

    asyncio.run(scenario())


def test_rejected_inbound_event_remains_diagnostic_without_failing_readiness(tmp_path):
    async def scenario():
        wal = LoginCommandWal(tmp_path / "login-wal")
        wal.record_rejected_event("qq_main", "C2C_MESSAGE_CREATE", "bad-message", 4,
                                  "INVALID_MESSAGE_STRUCTURE", 100)
        connector = FakeConnector(CONFIG, wal)
        connector._gateway_ready = True
        connector._gateway_last_activity = time.monotonic()
        connector._login_worker_running = True
        connector._login_worker_last_tick = time.monotonic()
        connector._inbound_worker_running = True
        connector._inbound_worker_last_tick = time.monotonic()

        health = await connector.command_health()

        assert health["status"] == "UP"
        assert health["inboundReady"] is True
        assert health["rejectedInboundEvents"] == 1

    asyncio.run(scenario())


def test_send_image_is_bounded_before_any_network_call():
    async def scenario():
        connector = FakeConnector(replace(CONFIG))
        try:
            await connector.send_image("allowed", "message", b"", "caption")
        except ValueError:
            pass
        else:
            raise AssertionError("empty image must be rejected")
    asyncio.run(scenario())


def test_duplicate_image_sequence_is_idempotent_success():
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append((path, payload))
            if path.endswith("/files"):
                return {"file_info": "uploaded"}
            raise RuntimeError(
                "QQ request failed with HTTP 400, code=40054005, reason=duplicated")

    async def scenario():
        connector = ReplyConnector()

        await connector.send_image("allowed", "message", b"image", "caption", 2)

        assert len(connector.requests) == 2

    asyncio.run(scenario())


def test_passive_image_success_uploads_and_sends_once():
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append((path, payload))
            return {"file_info": "uploaded"} if path.endswith("/files") else {}

    async def scenario():
        connector = ReplyConnector()
        await connector.send_image("allowed", "message", b"image", "caption", 2)
        assert len(connector.requests) == 2
        assert connector.requests[1][1]["msg_id"] == "message"
        assert connector.requests[1][1]["msg_seq"] == 2

    asyncio.run(scenario())


def test_expired_passive_image_reuses_upload_for_one_active_attempt():
    class ReplyConnector(FakeConnector):
        def __init__(self, active_failure=False):
            super().__init__()
            self.requests = []
            self.active_failure = active_failure

        async def _qq_request(self, method, path, payload=None):
            self.requests.append((path, payload))
            if path.endswith("/files"):
                return {"file_info": "uploaded"}
            if "msg_id" in payload:
                raise RuntimeError("code=40034024")
            if self.active_failure:
                raise RuntimeError("code=50000000")
            return {}

    async def scenario():
        connector = ReplyConnector()
        with patch("mytools_qq_connector.client.random.randint", return_value=4242):
            await connector.send_image("allowed", "message", b"image", "caption", 2)
        assert len(connector.requests) == 3
        assert connector.requests[1][1]["media"] == connector.requests[2][1]["media"]
        assert "msg_id" not in connector.requests[2][1]
        assert connector.requests[2][1]["msg_seq"] == 4242

        failed = ReplyConnector(active_failure=True)
        with pytest.raises(RuntimeError, match="50000000"):
            await failed.send_image("allowed", "message", b"image", "caption", 2)
        assert len(failed.requests) == 3

    asyncio.run(scenario())


def test_duplicate_active_image_response_is_not_treated_as_success():
    class ReplyConnector(FakeConnector):
        async def _qq_request(self, method, path, payload=None):
            if path.endswith("/files"):
                return {"file_info": "uploaded"}
            if "msg_id" in payload:
                raise RuntimeError("code=40034024")
            raise RuntimeError("code=40054005")

    async def scenario():
        with pytest.raises(RuntimeError, match="40054005"):
            await ReplyConnector().send_image(
                "allowed", "message", b"image", "caption", 2)

    asyncio.run(scenario())


def test_messaging_request_uses_current_inbound_contract():
    async def scenario():
        connector = CapturingConnector()
        await connector._messaging_receive(event(), "allowed", "message-1", "登录")
        payload = connector.request[2]
        assert payload["externalMessageId"] == "qq_main:C2C_MESSAGE_CREATE:message-1"
        assert "externalId" not in payload
    asyncio.run(scenario())


def test_scheduler_request_uses_business_identity():
    connector = FakeConnector()
    assert connector._internal_headers("http://scheduler/api/v1/task-instances", "") == {
        "Accept": "application/json",
        "X-Task-Service-Id": "qq-connector-service",
        "X-Task-Business-Token": "scheduler-token",
    }


def test_relogin_redrive_generation_uses_new_scheduler_and_action_keys():
    async def scenario():
        connector = CapturingConnector()
        assert await connector._create_relogin_task("message-1", 7) == "inbound-message"
        payload = connector.request[2]
        digest = hashlib.sha256(b"message-1").hexdigest()
        assert payload["idempotencyKey"] == f"qq-login:{digest}:g7"
        assert payload["businessId"] == f"qq-login:{digest}:g7"
        assert payload["parameters"]["requestId"] == f"qq_{digest}_g7"

    asyncio.run(scenario())


def test_relogin_scheduler_identity_is_bounded_for_long_message_id():
    async def scenario():
        connector = CapturingConnector()
        message_id = "message-" + "x" * 500
        await connector._create_relogin_task(message_id, 999_999_999)
        payload = connector.request[2]
        assert len(payload["idempotencyKey"]) <= 255
        assert len(payload["businessId"]) <= 128
        assert len(payload["parameters"]["requestId"]) <= 128
        assert message_id not in json.dumps(payload)

    asyncio.run(scenario())


def test_relogin_scheduler_identity_does_not_collapse_symbol_variants():
    async def identity(message_id):
        connector = CapturingConnector()
        await connector._create_relogin_task(message_id, 0)
        return connector.request[2]

    async def scenario():
        first = await identity("message-1")
        second = await identity("message_1")
        assert first["idempotencyKey"] != second["idempotencyKey"]
        assert first["businessId"] != second["businessId"]
        assert first["parameters"]["requestId"] != second["parameters"]["requestId"]

    asyncio.run(scenario())


def test_url_is_persisted_without_connector_level_acknowledgement():
    async def scenario():
        connector = FakeConnector()
        await connector.receive(event(content="https://example.test/file", message_id="message-url"))
        await asyncio.sleep(0)
        assert connector.replies == []
        assert connector.actions == ["persist"]
    asyncio.run(scenario())


def test_qq_attachment_is_normalized_for_messaging():
    async def scenario():
        connector = CapturingConnector()
        payload = event(content="", message_id="message-file")
        payload["d"]["attachments"] = [{"url": "https://example.test/photo.jpg",
            "content_type": "image/jpeg", "filename": "photo.jpg", "size": 123}]
        normalized = await connector._messaging_receive(
            payload, "allowed", "message-file", "")
        request = connector.request[2]
        assert normalized == "[empty]"
        assert request["parts"][1]["attachmentType"] == "IMAGE"
        assert request["parts"][1]["declaredSize"] == 123
    asyncio.run(scenario())


@pytest.mark.parametrize("error_code", ["40034024", "40034005"])
def test_expired_passive_reply_falls_back_to_active_message(error_code):
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append(payload)
            if len(self.requests) == 1:
                raise RuntimeError(
                    f"QQ request failed with HTTP 400, code={error_code}, reason=expired")
            return {"id": "sent"}

    async def scenario():
        connector = ReplyConnector()
        with patch("mytools_qq_connector.client.random.randint", return_value=4242):
            await QQConnector.send_text(connector, "allowed", "message-old", "done", 2)
        assert connector.requests == [
            {"msg_type": 0, "content": "done", "msg_id": "message-old", "msg_seq": 2},
            {"msg_type": 0, "content": "done", "msg_seq": 4242}]
    asyncio.run(scenario())


def test_late_completion_pages_are_sent_as_active_messages():
    async def scenario():
        connector = FakeConnector()
        common = {"sender": "allowed", "messageId": "message-old", "text": "done",
                  "eventType": "C2C_MESSAGE_CREATE"}
        await deliver_text(connector, {
            **common, "idempotencyKey": "automation-completion-run-page-8"})
        await deliver_text(connector, {
            **common, "idempotencyKey": "automation-completion-run-page-9"})
        assert connector.replies == [
            ("allowed", "message-old", "done", False),
            ("allowed", "message-old", "done", True),
        ]
    asyncio.run(scenario())


def test_progress_is_active_while_start_and_completion_stay_passive():
    async def scenario():
        connector = FakeConnector()
        common = {"sender": "allowed", "messageId": "message-current", "text": "status",
                  "eventType": "C2C_MESSAGE_CREATE"}
        await deliver_text(connector, {
            **common, "idempotencyKey": "automation-start-run"})
        await deliver_text(connector, {
            **common, "idempotencyKey": "automation-progress-run-action-25"})
        await deliver_text(connector, {
            **common, "idempotencyKey": "automation-progress-run-action-50"})
        await deliver_text(connector, {
            **common, "idempotencyKey": "automation-completion-run"})
        assert connector.replies == [
            ("allowed", "message-current", "status", False),
            ("allowed", "message-current", "status", True),
            ("allowed", "message-current", "status", True),
            ("allowed", "message-current", "status", False),
        ]
    asyncio.run(scenario())


def test_active_only_text_omits_passive_reply_fields():
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append(payload)
            return {"id": "sent"}

    async def scenario():
        connector = ReplyConnector()
        with patch("mytools_qq_connector.client.random.randint", return_value=4242):
            await QQConnector.send_text(connector, "allowed", "message-old", "page nine",
                                        1, active_only=True)
        assert connector.requests == [
            {"content": "page nine", "msg_type": 0, "msg_seq": 4242}]
    asyncio.run(scenario())


@pytest.mark.parametrize("event_type", ["AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"])
def test_guild_text_uses_only_supported_reply_fields(event_type):
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append((path, payload))
            return {"id": "sent"}

    async def scenario():
        connector = ReplyConnector()
        await QQConnector.send_text(
            connector, "channel", "message", "done", 2, event_type)
        assert connector.requests == [
            ("/channels/channel/messages", {"content": "done", "msg_id": "message"})]

    asyncio.run(scenario())


def test_duplicate_passive_sequence_is_treated_as_idempotent_success():
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append(payload)
            if len(self.requests) == 1:
                raise RuntimeError(
                    "QQ request failed with HTTP 400, code=40054005, reason=duplicated")
            return {"id": "sent"}

    async def scenario():
        connector = ReplyConnector()
        await QQConnector.send_text(connector, "allowed", "message-old", "done", 2)
        assert len(connector.requests) == 1
    asyncio.run(scenario())


def test_duplicate_active_response_is_not_treated_as_success():
    class ReplyConnector(FakeConnector):
        async def _qq_request(self, method, path, payload=None):
            if "msg_id" in payload:
                raise RuntimeError(
                    "QQ request failed with HTTP 400, code=40034024, reason=expired")
            raise RuntimeError(
                "QQ request failed with HTTP 400, code=40054005, reason=duplicated")

    async def scenario():
        connector = ReplyConnector()
        with pytest.raises(RuntimeError, match="40054005"):
            await QQConnector.send_text(
                connector, "allowed", "message-old", "done", 2)
        with pytest.raises(RuntimeError, match="40054005"):
            await QQConnector.send_text(
                connector, "allowed", "message-old", "done", 2,
                active_only=True)

    asyncio.run(scenario())


def test_nested_and_composite_attachments_are_deduplicated():
    async def scenario():
        connector = CapturingConnector()
        payload = event(content="", message_id="message-composite")
        payload["d"]["msg_elements"] = [{"payload": json.dumps({"attachments": [
            {"url": "https://example.test/nested.mp4", "filename": "nested.mp4",
             "content_type": "video/mp4", "size": 123}]})}]
        payload["d"]["content"] = ("[附件1] 类型:视频 文件名:nested.mp4 大小:123B "
                                     "URL:https://example.test/nested.mp4\n"
                                     "[附件2] 类型:图片 文件名:photo.jpg 大小:2KB "
                                     "URL:https://example.test/photo.jpg")
        await connector._messaging_receive(payload, "allowed", "message-composite",
                                           payload["d"]["content"])
        parts = [part for part in connector.request[2]["parts"]
                 if part["type"] == "ATTACHMENT"]
        by_url = {part["sourceUrl"]: part for part in parts}
        assert set(by_url) == {"https://example.test/photo.jpg",
                               "https://example.test/nested.mp4"}
        assert by_url["https://example.test/photo.jpg"]["declaredSize"] == 2048
    asyncio.run(scenario())


def test_msg_elements_preview_urls_are_not_treated_as_attachments():
    async def scenario():
        connector = CapturingConnector()
        payload = event(content="普通消息", message_id="message-preview")
        payload["d"]["msg_elements"] = [{"preview": {
            "url": "https://example.test/avatar.jpg", "title": "profile"}}]
        await connector._messaging_receive(payload, "allowed", "message-preview", "普通消息")
        parts = [part for part in connector.request[2]["parts"]
                 if part["type"] == "ATTACHMENT"]
        assert parts == []
    asyncio.run(scenario())


def test_login_worker_recovers_persisted_scheduler_task_after_restart(tmp_path):
    class RecoveringConnector(FakeConnector):
        def __init__(self, config, login_wal):
            super().__init__(config, login_wal)
            self.created_tasks = 0
            self.polled_tasks = []
            self.images = []

        async def _create_relogin_task(self, message_id, _generation):
            self.created_tasks += 1
            return "unexpected-task"

        async def _wait_relogin_task(self, task_id, deadline_at):
            self.polled_tasks.append(task_id)
            return "QR_READY", datetime.now(UTC).isoformat()

        async def _qr_bytes(self, requested_at):
            return b"png"

        async def send_image(self, sender, message_id, image, text, sequence=1):
            self.images.append((sender, message_id, image, sequence))

    async def scenario():
        wal_path = tmp_path / "login-wal"
        wal = LoginCommandWal(wal_path)
        wal.store(LoginCommand.create("qq_main", "recovered-message", "allowed", 300))
        entry = wal.load_batch(time.time(), 1)[0]
        wal.attach_task(entry, "existing-task")

        recovered = LoginCommandWal(wal_path)
        config = replace(CONFIG, login_worker_poll_seconds=0.01)
        connector = RecoveringConnector(config, recovered)
        stop = asyncio.Event()
        worker = asyncio.create_task(connector.run_login_worker(stop))
        for _ in range(100):
            if recovered.stats()["done"] == 1:
                break
            await asyncio.sleep(0.01)
        stop.set()
        await asyncio.wait_for(worker, timeout=1)

        assert connector.created_tasks == 0
        assert connector.polled_tasks == ["existing-task"]
        assert connector.images == [("allowed", "recovered-message", b"png", 2)]
        assert recovered.stats()["pending"] == 0
        assert recovered.stats()["done"] == 1

    asyncio.run(scenario())


def test_login_worker_reports_already_online_without_fetching_qr(tmp_path):
    class OnlineConnector(FakeConnector):
        def __init__(self, config, login_wal):
            super().__init__(config, login_wal)
            self.qr_fetches = 0

        async def _create_relogin_task(self, _message_id, _generation):
            return "online-task"

        async def _wait_relogin_task(self, _task_id, _deadline_at):
            return "ALREADY_ONLINE", ""

        async def _qr_bytes(self, _requested_at):
            self.qr_fetches += 1
            return b"unexpected"

    async def scenario():
        config = replace(CONFIG, login_worker_poll_seconds=0.01)
        wal = LoginCommandWal(tmp_path / "login-wal")
        connector = OnlineConnector(config, wal)
        await connector.receive(event())
        stop = asyncio.Event()
        worker = asyncio.create_task(connector.run_login_worker(stop))
        for _ in range(100):
            if wal.stats()["done"] == 1:
                break
            await asyncio.sleep(0.01)
        stop.set()
        await asyncio.wait_for(worker, timeout=1)

        assert connector.replies[-1][2] == "QQ 当前已登录，无需扫码。"
        assert connector.qr_fetches == 0
        assert wal.stats()["done"] == 1

    asyncio.run(scenario())


def test_dead_login_command_does_not_block_later_command_and_health_is_down(tmp_path):
    class IsolatingConnector(FakeConnector):
        def __init__(self, config, login_wal):
            super().__init__(config, login_wal)
            self.images = []

        async def _create_relogin_task(self, message_id, _generation):
            if message_id == "poison-message":
                raise RuntimeError("temporary scheduler failure")
            return "healthy-task"

        async def _wait_relogin_task(self, task_id, deadline_at):
            return "QR_READY", datetime.now(UTC).isoformat()

        async def _qr_bytes(self, requested_at):
            return b"png"

        async def send_image(self, sender, message_id, image, text, sequence=1):
            self.images.append(message_id)

        async def send_text(self, sender, message_id, text, sequence=1,
                            event_type="C2C_MESSAGE_CREATE", active_only=False):
            if message_id == "poison-message" and sequence == 2:
                raise RuntimeError("QQ unavailable")
            await super().send_text(sender, message_id, text, sequence,
                                    event_type, active_only)

    async def scenario():
        config = replace(CONFIG, login_max_attempts=1,
                         login_retry_max_seconds=0.01,
                         login_worker_poll_seconds=0.01)
        wal = LoginCommandWal(tmp_path / "login-wal")
        connector = IsolatingConnector(config, wal)
        await connector.receive(event(message_id="poison-message"))
        await connector.receive(event(message_id="healthy-message"))
        stop = asyncio.Event()
        worker = asyncio.create_task(connector.run_login_worker(stop))
        for _ in range(200):
            counts = wal.stats()
            if counts["dead"] == 1 and counts["done"] == 1:
                break
            await asyncio.sleep(0.01)

        connector._gateway_ready = True
        connector._gateway_last_activity = time.monotonic()
        connector._inbound_worker_running = True
        connector._inbound_worker_last_tick = time.monotonic()
        health = await connector.command_health()
        dead_record = next((tmp_path / "login-wal" / "dead").iterdir())
        wal.acknowledge_dead(dead_record.stem)
        recovered_health = await connector.command_health()
        stop.set()
        await asyncio.wait_for(worker, timeout=1)

        assert wal.stats()["dead"] == 0
        assert wal.stats()["done"] == 2
        assert connector.images == ["healthy-message"]
        assert health["status"] == "DOWN"
        assert health["loginCommandsReady"] is False
        assert health["deadLoginCommands"] == 1
        assert health["loginCommandErrorCode"] == "DEAD_LOGIN_COMMANDS"
        assert "poison-message" not in json.dumps(health)
        assert recovered_health["status"] == "UP"
        assert recovered_health["loginCommandsReady"] is True
        assert recovered_health["deadLoginCommands"] == 0
        assert recovered_health["loginCommandErrorCode"] is None

    asyncio.run(scenario())
