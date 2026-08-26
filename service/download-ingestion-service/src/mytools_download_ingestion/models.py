"""Domain models owned by the Download Ingestion service."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4


class DownloadStatus(StrEnum):
    """Download request lifecycle states."""

    ACCEPTED = "ACCEPTED"
    PLANNING = "PLANNING"
    RUNNING = "RUNNING"
    CANCELLING = "CANCELLING"
    CANCELLED = "CANCELLED"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class CreateDownloadRequest:
    """Input used to idempotently accept one download request."""

    idempotency_key: str
    source_type: str
    source_key: str
    request_kind: str
    parameters: dict[str, Any]
    owner_id: int = 0

    def __post_init__(self) -> None:
        """Validate stable request identity and scheduler-compatible fields."""
        values = (self.idempotency_key, self.source_type, self.source_key, self.request_kind)
        if any(not value or not value.strip() for value in values):
            raise ValueError("download request identity fields must not be blank")
        if len(self.idempotency_key) > 255 or len(self.source_key) > 255:
            raise ValueError("download request identity exceeds maximum length")
        if len(self.source_type) > 64 or len(self.request_kind) > 64:
            raise ValueError("download request type exceeds maximum length")
        if not isinstance(self.owner_id, int) or isinstance(self.owner_id, bool) or self.owner_id < 0:
            raise ValueError("download request owner is invalid")
        if not isinstance(self.parameters, dict):
            raise ValueError("download request parameters are invalid")
        if self.request_kind == "HTTP_ASSET":
            # HTTP 原子任务依赖这些稳定字段，必须在创建任务前拒绝不可执行请求。
            required = ("itemId", "url", "fileName")
            if any(not isinstance(self.parameters.get(key), str)
                   or not self.parameters[key].strip() for key in required):
                raise ValueError("HTTP asset parameters are incomplete")


@dataclass(frozen=True, slots=True)
class DownloadRequest:
    """Persistent download request aggregate."""

    id: UUID
    idempotency_key: str
    source_type: str
    source_key: str
    request_kind: str
    parameters: dict[str, Any]
    status: DownloadStatus
    owner_id: int = 0
    task_instance_id: UUID | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    updated_at: datetime = field(default_factory=lambda: datetime.now(UTC))

    @classmethod
    def accept(cls, request: CreateDownloadRequest) -> "DownloadRequest":
        """Create a new accepted aggregate before task binding."""
        return cls(
            id=uuid4(),
            idempotency_key=request.idempotency_key,
            source_type=request.source_type,
            source_key=request.source_key,
            request_kind=request.request_kind,
            parameters=dict(request.parameters),
            status=DownloadStatus.ACCEPTED,
            owner_id=request.owner_id,
        )
