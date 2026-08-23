"""适配器领域模型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4


class AdapterMode(StrEnum):
    """定义旁路流量模式。"""

    DISABLED = "DISABLED"
    SHADOW = "SHADOW"


class EventStatus(StrEnum):
    """定义旧事件的处理状态。"""

    RECEIVED = "RECEIVED"
    FORWARDED = "FORWARDED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class AcceptLegacyEvent:
    """表示一个已脱敏的旧下载请求事件。"""

    event_id: str
    source_type: str
    source_key: str
    request_kind: str
    parameters: dict[str, Any]

    def __post_init__(self) -> None:
        """校验稳定身份字段和大小边界。"""
        values = (self.event_id, self.source_type, self.source_key, self.request_kind)
        if any(not value or not value.strip() for value in values):
            raise ValueError("event identity fields must not be blank")
        if len(self.event_id) > 255 or len(self.source_key) > 255:
            raise ValueError("event identity exceeds maximum length")
        if len(self.source_type) > 64 or len(self.request_kind) > 64:
            raise ValueError("event type exceeds maximum length")
        self._reject_sensitive_keys(self.parameters)

    @classmethod
    def _reject_sensitive_keys(cls, value: Any) -> None:
        """递归拒绝不应跨越服务边界的凭据字段。"""
        blocked = {"authorization", "cookie", "password", "secret", "token"}
        if isinstance(value, dict):
            for key, nested in value.items():
                normalized = str(key).replace("_", "").replace("-", "").lower()
                if any(name in normalized for name in blocked):
                    raise ValueError("event parameters contain sensitive field")
                cls._reject_sensitive_keys(nested)
        elif isinstance(value, list):
            for nested in value:
                cls._reject_sensitive_keys(nested)


@dataclass(frozen=True, slots=True)
class LegacyEvent:
    """表示适配器持久化的收件箱事件。"""

    id: UUID
    event_id: str
    source_type: str
    source_key: str
    request_kind: str
    parameters: dict[str, Any]
    status: EventStatus
    download_request_id: UUID | None = None
    error_code: str | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    updated_at: datetime = field(default_factory=lambda: datetime.now(UTC))

    @classmethod
    def receive(cls, command: AcceptLegacyEvent) -> "LegacyEvent":
        """创建一个尚未转发的新事件。"""
        return cls(uuid4(), command.event_id, command.source_type, command.source_key,
                   command.request_kind, dict(command.parameters), EventStatus.RECEIVED)
