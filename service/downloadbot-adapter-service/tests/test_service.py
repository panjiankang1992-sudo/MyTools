from uuid import uuid4

import pytest

from mytools_downloadbot_adapter.models import AcceptLegacyEvent, AdapterMode, EventStatus
from mytools_downloadbot_adapter.service import AdapterService, InMemoryEventRepository


class FakeClient:
    def __init__(self, fail: bool = False):
        self.fail = fail
        self.calls = 0
        self.request_id = uuid4()

    def create_request(self, _command):
        self.calls += 1
        if self.fail:
            raise RuntimeError("unavailable")
        return self.request_id


def command(parameters=None):
    return AcceptLegacyEvent("legacy-1", "MESSAGE", "message-1", "HTTP_ASSET",
                             parameters or {"url": "https://example.com/a"})


def test_disabled_mode_only_records_event():
    client = FakeClient()
    result = AdapterService(InMemoryEventRepository(), client, AdapterMode.DISABLED).accept(command())
    assert result.status is EventStatus.RECEIVED
    assert result.download_request_id is None
    assert client.calls == 0


def test_shadow_mode_forwards_once_and_replay_is_idempotent():
    repository = InMemoryEventRepository()
    client = FakeClient()
    service = AdapterService(repository, client, AdapterMode.SHADOW)
    first = service.accept(command())
    second = service.accept(command())
    assert first.status is EventStatus.FORWARDED
    assert second.download_request_id == client.request_id
    assert client.calls == 1


def test_same_event_id_with_changed_payload_is_rejected():
    service = AdapterService(InMemoryEventRepository(), FakeClient(), AdapterMode.DISABLED)
    service.accept(command())
    with pytest.raises(ValueError, match="idempotency conflict"):
        service.accept(command({"url": "https://example.com/b"}))


def test_failed_forward_can_be_retried_after_dependency_recovers():
    repository = InMemoryEventRepository()
    client = FakeClient(fail=True)
    service = AdapterService(repository, client, AdapterMode.SHADOW)
    assert service.accept(command()).status is EventStatus.FAILED
    client.fail = False
    result = service.accept(command())
    assert result.status is EventStatus.FORWARDED
    assert result.error_code is None
    assert client.calls == 2


def test_recorded_event_can_be_forwarded_after_mode_change():
    repository = InMemoryEventRepository()
    client = FakeClient()
    AdapterService(repository, client, AdapterMode.DISABLED).accept(command())
    result = AdapterService(repository, client, AdapterMode.SHADOW).accept(command())
    assert result.status is EventStatus.FORWARDED
    assert client.calls == 1


def test_sensitive_parameters_are_rejected():
    with pytest.raises(ValueError, match="sensitive field"):
        command({"headers": {"Authorization": "Bearer unsafe"}})
