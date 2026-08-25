from io import BytesIO
from datetime import UTC, datetime
import json
from pathlib import Path

import pytest

from mytools_onebot_connector.connector import OneBotClient, copy_bounded, mapped_local_file
from mytools_onebot_connector.models import ContentSource
from mytools_onebot_connector.repository import InMemoryAccountRepository
from mytools_onebot_connector.service import OneBotConnectorService, validated_account

ACCOUNT = {"externalKey": "qq_primary", "httpBaseUrl": "http://127.0.0.1:3000",
           "secretRef": "env://ONEBOT_PRIMARY_TOKEN", "hostQqRoot": "/opt/napcat/qq",
           "containerQqRoot": "/app/.config/QQ", "enabled": True}
REQUEST = {"channelType": "ONEBOT", "accountKey": "qq_primary", "attachmentType": "FILE",
           "providerFileId": "opaque-file-id"}


class FakeClient:
    def __init__(self, public_url=None):
        self._public_url = public_url

    def prepare(self, account, provider_file_id):
        assert provider_file_id == "opaque-file-id"
        return ContentSource(account, local_path="/safe/file")

    def public_url(self, _source):
        return self._public_url

    def stream(self, _source, output, maximum_bytes):
        assert maximum_bytes == 1024
        output.write(b"content")
        return 7


def create_service(enabled=True, public_url=None, relogin_request_path=None, qr_path=None):
    repository = InMemoryAccountRepository()
    application = OneBotConnectorService(repository, FakeClient(public_url), enabled, 1024,
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


def test_qr_must_be_fresh_png_and_bounded(tmp_path: Path):
    qr_path = tmp_path / "qrcode.png"
    qr_path.write_bytes(b"\x89PNG\r\n\x1a\ncontent")
    application = create_service(qr_path=str(qr_path))
    requested_at = datetime.fromtimestamp(qr_path.stat().st_mtime - 1, UTC).isoformat()
    source, size = application.prepare_qr({"accountKey": "qq_primary", "requestedAt": requested_at})
    output = BytesIO()
    assert application.stream_qr(source, output) == size
    assert output.getvalue().startswith(b"\x89PNG")


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
