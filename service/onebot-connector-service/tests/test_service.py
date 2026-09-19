from io import BytesIO
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
import json
from pathlib import Path
from threading import Event
from urllib.error import HTTPError, URLError
from uuid import uuid4

import pytest

from mytools_onebot_connector.connector import OneBotClient, copy_bounded, mapped_local_file
from mytools_onebot_connector.models import (ContentSource, ProviderPermanentRejectionError,
                                             ProviderRequestNotStartedError,
                                             ProviderTransientRejectionError, TextReply)
from mytools_onebot_connector.repository import InMemoryAccountRepository
from mytools_onebot_connector.service import OneBotConnectorService, validated_account

ACCOUNT = {"externalKey": "qq_primary", "httpBaseUrl": "http://127.0.0.1:3000",
           "secretRef": "env://ONEBOT_PRIMARY_TOKEN", "hostQqRoot": "/opt/napcat/qq",
           "containerQqRoot": "/app/.config/QQ", "enabled": True}
REQUEST = {"channelType": "ONEBOT", "accountKey": "qq_primary", "attachmentType": "FILE",
           "providerFileId": "opaque-file-id"}
TEXT_REQUEST = {"accountKey": "qq_primary", "messageType": "private", "targetId": "123",
                "messageId": "7", "text": "done", "idempotencyKey": "reply-7"}


class FakeClient:
    def __init__(self, public_url=None, send_error=None, online=False,
                 online_error=None):
        self._public_url = public_url
        self._send_error = send_error
        self._online = online
        self._online_error = online_error
        self.send_calls = 0

    def prepare(self, account, provider_file_id):
        assert provider_file_id == "opaque-file-id"
        return ContentSource(account, local_path="/safe/file")

    def public_url(self, _source):
        return self._public_url

    def stream(self, _source, output, maximum_bytes):
        assert maximum_bytes == 1024
        output.write(b"content")
        return 7

    def send_text(self, _account, message_type, target_id, text):
        assert (message_type, target_id, text) == ("private", "123", "done")
        self.send_calls += 1
        if self._send_error is not None:
            raise self._send_error
        return {"message_id": 88}

    def get_forward_message(self, _account, forward_id):
        assert forward_id == "forward-1"
        return [{"content": [{"type": "text", "data": {"text": "nested"}}]}]

    def is_online(self, _account):
        if self._online_error is not None:
            raise self._online_error
        return self._online


def create_service(enabled=True, public_url=None, relogin_request_path=None, qr_path=None,
                   online=False, online_error=None):
    repository = InMemoryAccountRepository()
    application = OneBotConnectorService(
        repository, FakeClient(public_url, online=online, online_error=online_error), enabled, 1024,
                                         relogin_request_path, qr_path)
    application.register(ACCOUNT)
    return application


def test_connector_is_default_safe_when_disabled():
    with pytest.raises(RuntimeError, match="disabled"):
        create_service(enabled=False).resolve(REQUEST)


def test_resolve_returns_only_public_url_or_stream_mode():
    assert create_service(public_url="https://files.example.test/file").resolve(REQUEST) == {
        "mode": "PUBLIC_URL", "downloadUrl": "https://files.example.test/file"}
    assert create_service().resolve(REQUEST) == {"mode": "STREAM"}


def test_account_registration_is_idempotent_and_never_returns_secret_reference():
    result = create_service().register(ACCOUNT)
    assert result["externalKey"] == "qq_primary"
    assert "secret" not in str(result).lower()


def test_account_route_must_be_loopback_and_secret_must_be_indirect():
    for override in ({"httpBaseUrl": "https://onebot.example.test"}, {"secretRef": "plain-token"},
                     {"enabled": "false"}):
        with pytest.raises(ValueError):
            validated_account(ACCOUNT | override)


def test_local_path_mapping_stays_under_host_root(tmp_path: Path):
    host = tmp_path / "qq"
    target = host / "data" / "file.bin"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"data")
    account = validated_account(ACCOUNT | {"hostQqRoot": str(host)})
    assert mapped_local_file(account, "/app/.config/QQ/data/file.bin") == target
    assert mapped_local_file(account, "/app/.config/QQ/../../etc/passwd") is None


def test_public_url_rejects_signed_urls():
    account = validated_account(ACCOUNT)
    client = OneBotClient(resolver=lambda *_args, **_kwargs: [
        (None, None, None, None, ("93.184.216.34", 443))])
    assert client.public_url(ContentSource(account, url="https://example.com/file")) \
        == "https://example.com/file"
    assert client.public_url(ContentSource(account, url="https://example.com/file?signature=x")) is None


def test_bounded_copy_fails_before_writing_over_limit_chunk():
    output = BytesIO()
    with pytest.raises(ValueError, match="exceeds"):
        copy_bounded(BytesIO(b"12345"), output, 4)
    assert output.getvalue() == b""


def test_stream_content_uses_service_ceiling():
    output = BytesIO()
    application = create_service()
    assert application.stream_content(application.prepare_content(REQUEST), output) == 7
    assert output.getvalue() == b"content"


def test_relogin_request_is_atomic_and_does_not_accept_a_path(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path))
    result = application.request_relogin({"accountKey": "qq_primary", "requestId": "request_1"})
    assert result["status"] == "REQUESTED"
    document = json.loads(request_path.read_text())
    assert document["accountId"] == "qq_primary"
    assert document["requestId"] == "request_1"
    assert request_path.stat().st_mode & 0o777 == 0o600
    with pytest.raises(ValueError):
        application.request_relogin({"accountKey": "qq_primary", "requestId": "request_2",
                                     "path": "/tmp/other"})


def test_relogin_replay_does_not_repeat_an_acknowledged_restart(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path))
    request = {"accountKey": "qq_primary", "requestId": "request_1"}

    first = application.request_relogin(request)
    request_path.replace(request_path.with_name("relogin.request.ack"))
    replay = application.request_relogin(request)

    assert replay == first | {"status": "RESTARTED"}
    assert not request_path.exists()
    state = json.loads(request_path.with_name("relogin.request.state.json").read_text())
    assert len(state["requests"]) == 1
    assert next(iter(state["attempts"].values())) == 1


def test_failed_relogin_action_is_rearmed_with_a_bounded_attempt(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path))
    request = {"accountKey": "qq_primary", "requestId": "request_1"}

    first = application.request_relogin(request)
    request_path.replace(request_path.with_name("relogin.request.failed"))
    replay = application.request_relogin(request)

    assert replay == first
    marker = json.loads(request_path.read_text())
    assert marker["attempt"] == 2


def test_missing_unacknowledged_trigger_is_rearmed_instead_of_sticking(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path))
    request = {"accountKey": "qq_primary", "requestId": "request_1"}

    application.request_relogin(request)
    request_path.unlink()
    application.request_relogin(request)

    marker = json.loads(request_path.read_text())
    assert marker["attempt"] == 2


def test_relogin_action_stops_rearming_after_fixed_attempt_limit(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path))
    request = {"accountKey": "qq_primary", "requestId": "request_1"}

    result = application.request_relogin(request)
    for _ in range(3):
        request_path.replace(request_path.with_name("relogin.request.failed"))
        result = application.request_relogin(request)
        if result["status"] == "FAILED":
            break

    assert result["status"] == "FAILED"
    assert not request_path.exists()


def test_already_online_relogin_returns_explicit_result_without_trigger(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path), online=True)

    result = application.request_relogin(
        {"accountKey": "qq_primary", "requestId": "request_1"})

    assert result == {"accountKey": "qq_primary", "requestId": "request_1",
                      "status": "ALREADY_ONLINE"}
    assert not request_path.exists()


def test_new_relogin_request_does_not_replace_an_active_trigger(tmp_path: Path):
    request_path = tmp_path / "runtime" / "relogin.request"
    application = create_service(relogin_request_path=str(request_path))

    application.request_relogin({"accountKey": "qq_primary", "requestId": "request_1"})
    with pytest.raises(RuntimeError, match="another OneBot relogin action is pending"):
        application.request_relogin(
            {"accountKey": "qq_primary", "requestId": "request_2"})

    assert json.loads(request_path.read_text())["requestId"] == "request_1"


def test_qr_must_be_fresh_png_and_bounded(tmp_path: Path):
    qr_path = tmp_path / "qrcode.png"
    qr_path.write_bytes(b"\x89PNG\r\n\x1a\ncontent")
    application = create_service(qr_path=str(qr_path))
    requested_at = datetime.fromtimestamp(qr_path.stat().st_mtime - 1, UTC).isoformat()
    source, size = application.prepare_qr({"accountKey": "qq_primary", "requestedAt": requested_at})
    output = BytesIO()
    assert application.stream_qr(source, output) == size
    assert output.getvalue().startswith(b"\x89PNG")


def test_missing_qr_is_reported_as_temporarily_unavailable(tmp_path: Path):
    application = create_service(qr_path=str(tmp_path / "qrcode.png"))
    with pytest.raises(RuntimeError, match="fresh OneBot login QR is not available"):
        application.prepare_qr({"accountKey": "qq_primary",
                                "requestedAt": datetime.now(UTC).isoformat()})


def test_send_text_and_expand_forward_use_registered_account():
    application = create_service()
    sent = application.send_text(TEXT_REQUEST)
    assert sent == {"status": "SENT", "providerMessageId": "88"}
    assert application.expand_forward({"accountKey": "qq_primary", "forwardId": "forward-1"}) == {
        "messages": [{"content": [{"type": "text", "data": {"text": "nested"}}]}]}


def test_send_text_replay_returns_original_provider_id_without_resending():
    repository = InMemoryAccountRepository()
    client = FakeClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    first = application.send_text(TEXT_REQUEST)
    replay = application.send_text(TEXT_REQUEST)

    assert first == replay == {"status": "SENT", "providerMessageId": "88"}
    assert client.send_calls == 1


@pytest.mark.parametrize("override", [
    {"messageType": "group"},
    {"targetId": "124"},
    {"messageId": "8"},
    {"text": "different"},
])
def test_send_text_rejects_same_idempotency_key_with_different_payload(override):
    repository = InMemoryAccountRepository()
    client = FakeClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)
    application.send_text(TEXT_REQUEST)

    with pytest.raises(ValueError, match="idempotency conflict"):
        application.send_text(TEXT_REQUEST | override)

    assert client.send_calls == 1


def test_invalid_text_request_does_not_claim_idempotency_key():
    repository = InMemoryAccountRepository()
    client = FakeClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(ValueError, match="text request is invalid"):
        application.send_text(TEXT_REQUEST | {"targetId": "not-a-number"})

    assert application.send_text(TEXT_REQUEST) == {"status": "SENT", "providerMessageId": "88"}
    assert client.send_calls == 1


def test_provider_failure_becomes_uncertain_and_is_never_automatically_resent():
    repository = InMemoryAccountRepository()
    client = FakeClient(send_error=RuntimeError("provider timeout"))
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="outcome is uncertain"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="manual provider verification"):
        application.send_text(TEXT_REQUEST)

    assert client.send_calls == 1


@pytest.mark.parametrize("safe_error", [
    ProviderRequestNotStartedError("request was not started"),
    ProviderTransientRejectionError("provider temporarily rejected request"),
])
def test_definite_pre_send_failure_is_retried_after_persistent_backoff(safe_error):
    now = [datetime(2026, 9, 8, tzinfo=UTC)]
    repository = InMemoryAccountRepository()
    client = FakeClient(send_error=safe_error)
    application = OneBotConnectorService(
        repository, client, True, 1024, clock=lambda: now[0])
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="was not sent; retry later"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="was not sent; retry later"):
        application.send_text(TEXT_REQUEST)
    assert client.send_calls == 1

    now[0] += timedelta(seconds=1)
    client._send_error = None
    assert application.send_text(TEXT_REQUEST) == {"status": "SENT", "providerMessageId": "88"}
    assert client.send_calls == 2


def test_definite_pre_send_retries_are_bounded():
    now = [datetime(2026, 9, 8, tzinfo=UTC)]
    started_at = now[0]
    repository = InMemoryAccountRepository()
    client = FakeClient(send_error=ProviderRequestNotStartedError("request was not started"))
    application = OneBotConnectorService(repository, client, True, 1024, clock=lambda: now[0])
    application.register(ACCOUNT)

    for delay in (1, 2, 4, 8, 16, 32, 60, 60, 60):
        with pytest.raises(RuntimeError, match="retry later"):
            application.send_text(TEXT_REQUEST)
        now[0] += timedelta(seconds=delay)
    exhausted_at = now[0]
    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)
    now[0] += timedelta(minutes=1)
    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)

    assert client.send_calls == 10
    assert exhausted_at - started_at == timedelta(seconds=243)


def test_permanent_provider_rejection_fails_without_retrying():
    repository = InMemoryAccountRepository()
    client = FakeClient(send_error=ProviderPermanentRejectionError("invalid target"))
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)

    assert client.send_calls == 1


def test_committed_prepared_claim_is_safely_resumed_after_caller_crash():
    class ClaimAckLostRepository(InMemoryAccountRepository):
        def __init__(self):
            super().__init__()
            self.fail_once = True

        def claim_text_reply(self, reply):
            claim = super().claim_text_reply(reply)
            if claim.claimed and self.fail_once:
                self.fail_once = False
                raise RuntimeError("claim commit acknowledgement was lost")
            return claim

    repository = ClaimAckLostRepository()
    client = FakeClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="claim commit acknowledgement"):
        application.send_text(TEXT_REQUEST)
    assert client.send_calls == 0
    assert application.send_text(TEXT_REQUEST) == {"status": "SENT", "providerMessageId": "88"}
    assert client.send_calls == 1


def test_stale_in_flight_reply_becomes_uncertain_without_resending():
    now = datetime(2026, 9, 8, tzinfo=UTC)
    old = now - timedelta(minutes=3)
    repository = InMemoryAccountRepository()
    client = FakeClient()
    application = OneBotConnectorService(
        repository, client, True, 1024, clock=lambda: now, text_reply_stale_seconds=120)
    application.register(ACCOUNT)
    reply = TextReply.create("qq_primary", "reply-7", "private", "123", "7", "done", old)
    repository.claim_text_reply(reply)
    repository.start_text_reply(reply.id, 0, uuid4(), old)

    with pytest.raises(RuntimeError, match="manual provider verification"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="manual provider verification"):
        application.send_text(TEXT_REQUEST)

    assert client.send_calls == 0


def test_database_failure_after_provider_success_never_causes_a_resend():
    class LostUpdateRepository(InMemoryAccountRepository):
        def mark_text_reply_sent(self, reply_id, attempt_token, provider_message_id):
            raise RuntimeError("database connection was lost")

    repository = LostUpdateRepository()
    client = FakeClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="manual provider verification"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="manual provider verification"):
        application.send_text(TEXT_REQUEST)

    assert client.send_calls == 1


def test_commit_ack_loss_recovers_only_from_explicit_sent_state():
    class CommittedThenRaisedRepository(InMemoryAccountRepository):
        def mark_text_reply_sent(self, reply_id, attempt_token, provider_message_id):
            super().mark_text_reply_sent(reply_id, attempt_token, provider_message_id)
            raise RuntimeError("commit acknowledgement was lost")

    repository = CommittedThenRaisedRepository()
    client = FakeClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    assert application.send_text(TEXT_REQUEST) == {"status": "SENT", "providerMessageId": "88"}
    assert application.send_text(TEXT_REQUEST) == {"status": "SENT", "providerMessageId": "88"}
    assert client.send_calls == 1


def test_concurrent_replay_does_not_call_provider_twice():
    class BlockingClient(FakeClient):
        def __init__(self):
            super().__init__()
            self.started = Event()
            self.release = Event()

        def send_text(self, account, message_type, target_id, text):
            self.started.set()
            assert self.release.wait(2)
            return super().send_text(account, message_type, target_id, text)

    repository = InMemoryAccountRepository()
    client = BlockingClient()
    application = OneBotConnectorService(repository, client, True, 1024)
    application.register(ACCOUNT)

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(application.send_text, TEXT_REQUEST)
        assert client.started.wait(1)
        with pytest.raises(RuntimeError, match="in progress"):
            application.send_text(TEXT_REQUEST)
        client.release.set()
        assert first.result() == {"status": "SENT", "providerMessageId": "88"}

    assert client.send_calls == 1


class FakeResponse(BytesIO):
    status = 200
    headers = {}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


def test_get_file_uses_fixed_action_and_bearer_token():
    captured = {}

    def opener(request, timeout):
        captured.update(url=request.full_url, token=request.get_header("Authorization"),
                        payload=json.loads(request.data), timeout=timeout)
        return FakeResponse(json.dumps({"status": "ok", "retcode": 0,
                                        "data": {"url": "https://example.com/file"}}).encode())

    client = OneBotClient(secret_resolver=lambda _ref: "provider-token", opener=opener)
    assert client.get_file(validated_account(ACCOUNT), "opaque") == {
        "url": "https://example.com/file"}
    assert captured == {"url": "http://127.0.0.1:3000/get_file", "token": "Bearer provider-token",
                        "payload": {"file": "opaque", "file_id": "opaque"}, "timeout": 30}


def test_get_file_rejects_oversized_response():
    client = OneBotClient(secret_resolver=lambda _ref: "token",
                          opener=lambda *_args, **_kwargs: FakeResponse(b"x" * (1024 * 1024 + 1)))
    with pytest.raises(RuntimeError, match="too large"):
        client.get_file(validated_account(ACCOUNT), "opaque")


def test_send_text_statically_classifies_secret_resolution_before_request():
    def missing_secret(_secret_ref):
        raise ValueError("sensitive secret reference")

    client = OneBotClient(secret_resolver=missing_secret,
                          opener=lambda *_args, **_kwargs: pytest.fail("request must not start"))

    with pytest.raises(ProviderRequestNotStartedError, match="request was not started") as captured:
        client.send_text(validated_account(ACCOUNT), "private", "123", "done")

    assert "sensitive" not in str(captured.value)


def test_send_text_statically_classifies_connection_refused_before_request():
    def refused(_request, timeout):
        assert timeout == 30
        raise URLError(ConnectionRefusedError("sensitive socket detail"))

    client = OneBotClient(secret_resolver=lambda _secret_ref: "token", opener=refused)

    with pytest.raises(ProviderRequestNotStartedError, match="request was not started") as captured:
        client.send_text(validated_account(ACCOUNT), "private", "123", "done")

    assert "sensitive" not in str(captured.value)


def test_send_text_classifies_http_rejections_before_service_retry_decision():
    """明确 HTTP 响应应按状态分类，不能误报为提交结果不确定。"""
    account = validated_account(ACCOUNT)

    for status, expected in ((503, ProviderTransientRejectionError),
                             (400, ProviderPermanentRejectionError)):
        def rejected(request, timeout, response_status=status):
            raise HTTPError(request.full_url, response_status, "rejected", {}, None)

        client = OneBotClient(secret_resolver=lambda _secret_ref: "token", opener=rejected)
        with pytest.raises(expected):
            client.send_text(account, "private", "123", "done")


def test_transient_retcode_retries_then_succeeds():
    """NapCat 瞬时返回码应持久退避后安全重试。"""
    now = [datetime(2026, 9, 8, tzinfo=UTC)]
    responses = iter([
        {"status": "failed", "retcode": 100},
        {"status": "ok", "retcode": 0, "data": {"message_id": 88}},
    ])

    def opener(_request, timeout):
        assert timeout == 30
        return FakeResponse(json.dumps(next(responses)).encode())

    repository = InMemoryAccountRepository()
    application = OneBotConnectorService(
        repository, OneBotClient(secret_resolver=lambda _ref: "token", opener=opener),
        True, 1024, clock=lambda: now[0])
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="retry later"):
        application.send_text(TEXT_REQUEST)
    now[0] += timedelta(seconds=1)
    assert application.send_text(TEXT_REQUEST) == {"status": "SENT", "providerMessageId": "88"}


def test_transient_retcode_retries_are_bounded():
    """持续瞬时返回码达到预算后必须进入 FAILED。"""
    now = [datetime(2026, 9, 8, tzinfo=UTC)]
    calls = [0]

    def opener(_request, timeout):
        assert timeout == 30
        calls[0] += 1
        return FakeResponse(json.dumps({"status": "failed", "retcode": 100}).encode())

    repository = InMemoryAccountRepository()
    application = OneBotConnectorService(
        repository, OneBotClient(secret_resolver=lambda _ref: "token", opener=opener),
        True, 1024, clock=lambda: now[0])
    application.register(ACCOUNT)

    for delay in (1, 2, 4, 8, 16, 32, 60, 60, 60):
        with pytest.raises(RuntimeError, match="retry later"):
            application.send_text(TEXT_REQUEST)
        now[0] += timedelta(seconds=delay)
    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)
    assert calls[0] == 10


def test_permanent_retcode_fails_without_second_provider_call():
    """明确永久返回码应直接失败，后续幂等重放不得再次发送。"""
    calls = [0]

    def opener(_request, timeout):
        assert timeout == 30
        calls[0] += 1
        return FakeResponse(json.dumps({"status": "failed", "retcode": 1400}).encode())

    repository = InMemoryAccountRepository()
    application = OneBotConnectorService(
        repository, OneBotClient(secret_resolver=lambda _ref: "token", opener=opener), True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="retry limit is exhausted"):
        application.send_text(TEXT_REQUEST)
    assert calls[0] == 1


def test_response_parse_interruption_remains_uncertain():
    """请求已提交但响应无法解析时不得自动重发。"""
    calls = [0]

    def opener(_request, timeout):
        assert timeout == 30
        calls[0] += 1
        return FakeResponse(b"{")

    repository = InMemoryAccountRepository()
    application = OneBotConnectorService(
        repository, OneBotClient(secret_resolver=lambda _ref: "token", opener=opener), True, 1024)
    application.register(ACCOUNT)

    with pytest.raises(RuntimeError, match="outcome is uncertain"):
        application.send_text(TEXT_REQUEST)
    with pytest.raises(RuntimeError, match="manual provider verification"):
        application.send_text(TEXT_REQUEST)
    assert calls[0] == 1
