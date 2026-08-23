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
        )
