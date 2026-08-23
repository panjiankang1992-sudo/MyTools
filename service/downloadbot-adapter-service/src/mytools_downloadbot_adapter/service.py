"""DownloadBot 旁路事件编排。"""

from __future__ import annotations

from dataclasses import replace
from typing import Protocol
from uuid import UUID

from .models import AcceptLegacyEvent, AdapterMode, EventStatus, LegacyEvent


class EventRepository(Protocol):
    """定义适配器收件箱仓储。"""

    def find_by_event_id(self, event_id: str) -> LegacyEvent | None:
        """按旧事件标识查询。"""

    def insert(self, event: LegacyEvent) -> LegacyEvent:
        """插入一个新事件。"""

    def update(self, event: LegacyEvent) -> LegacyEvent:
        """更新事件处理结果。"""


class DownloadIngestionClient(Protocol):
    """定义下载接入服务客户端。"""

    def create_request(self, command: AcceptLegacyEvent) -> UUID:
        """幂等创建一个旁路下载请求。"""


class AdapterService:
    """持久化旧事件并按显式模式转发。"""

    def __init__(self, repository: EventRepository, client: DownloadIngestionClient,
                 mode: AdapterMode):
        self._repository = repository
        self._client = client
        self._mode = mode

    def accept(self, command: AcceptLegacyEvent) -> LegacyEvent:
        """幂等接受事件；仅影子模式调用新下载服务。"""
        existing = self._repository.find_by_event_id(command.event_id)
        if existing is not None:
            self._assert_same(existing, command)
            if (existing.status in (EventStatus.RECEIVED, EventStatus.FAILED)
                    and self._mode is AdapterMode.SHADOW):
                return self._forward(existing, command)
            return existing

        event = self._repository.insert(LegacyEvent.receive(command))
        self._assert_same(event, command)
        if self._mode is AdapterMode.DISABLED:
            return event
        return self._forward(event, command)

    def _forward(self, event: LegacyEvent, command: AcceptLegacyEvent) -> LegacyEvent:
        try:
            request_id = self._client.create_request(command)
            return self._repository.update(replace(
                event, status=EventStatus.FORWARDED, download_request_id=request_id,
                error_code=None))
        except Exception:
            failed = replace(event, status=EventStatus.FAILED,
                             error_code="DOWNLOADBOT_ADAPTER_001")
            self._repository.update(failed)
            return failed

    @staticmethod
    def _assert_same(event: LegacyEvent, command: AcceptLegacyEvent) -> None:
        if (event.source_type, event.source_key, event.request_kind, event.parameters) != (
                command.source_type, command.source_key, command.request_kind,
                command.parameters):
            raise ValueError("event idempotency conflict")


class InMemoryEventRepository:
    """为测试和本地验证提供内存仓储。"""

    def __init__(self) -> None:
        self._events: dict[str, LegacyEvent] = {}

    def find_by_event_id(self, event_id: str) -> LegacyEvent | None:
        """按旧事件标识查询。"""
        return self._events.get(event_id)

    def insert(self, event: LegacyEvent) -> LegacyEvent:
        """插入一个新事件。"""
        existing = self._events.setdefault(event.event_id, event)
        return existing

    def update(self, event: LegacyEvent) -> LegacyEvent:
        """更新事件处理结果。"""
        self._events[event.event_id] = event
        return event
