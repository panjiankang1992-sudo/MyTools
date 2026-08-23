"""Download request application service and scheduler boundary."""

from __future__ import annotations

from dataclasses import replace
from typing import Protocol
from uuid import UUID

from .models import CreateDownloadRequest, DownloadRequest, DownloadStatus

TASK_NAMES = {
    "HTTP_ASSET": "download_http_asset",
    "X_MEDIA": "download_x_media",
    "WEB_ARCHIVE": "download_web_archive",
    "PIKPAK_ASSET": "download_pikpak_asset",
    "MAGNET_ASSET": "download_magnet_asset",
    "MESSAGE_ATTACHMENT": "download_message_attachment",
    "LOCAL_IMPORT": "download_local_import",
}


class DownloadRequestRepository(Protocol):
    """Persistence operations required by the application service."""

    def find_by_idempotency_key(self, key: str) -> DownloadRequest | None:
        """Return an existing request by its global idempotency key."""

    def insert(self, request: DownloadRequest) -> DownloadRequest:
        """Persist a newly accepted request."""

    def bind_task(self, request_id: UUID, task_instance_id: UUID) -> DownloadRequest:
        """Bind the scheduler parent task and move the request to running."""


class TaskScheduler(Protocol):
    """Task Scheduler operations used by download orchestration."""

    def create_task(self, *, task_name: str, idempotency_key: str,
                    business_id: str, parameters: dict) -> UUID:
        """Idempotently create a scheduler task and return its instance ID."""


class DownloadRequestService:
    """Accept download requests and bind each request to one parent task."""

    def __init__(self, repository: DownloadRequestRepository, scheduler: TaskScheduler):
        self._repository = repository
        self._scheduler = scheduler

    def create(self, command: CreateDownloadRequest) -> DownloadRequest:
        """Idempotently create a request and its scheduler task binding."""
        existing = self._repository.find_by_idempotency_key(command.idempotency_key)
        if existing is not None:
            return existing
        task_name = TASK_NAMES.get(command.request_kind)
        if task_name is None:
            raise ValueError(f"unsupported download request kind: {command.request_kind}")
        accepted = self._repository.insert(DownloadRequest.accept(command))
        task_id = self._scheduler.create_task(
            task_name=task_name,
            idempotency_key=f"download:{accepted.idempotency_key}",
            business_id=str(accepted.id),
            parameters={"downloadRequestId": str(accepted.id), **accepted.parameters},
        )
        return self._repository.bind_task(accepted.id, task_id)


class InMemoryDownloadRequestRepository:
    """Deterministic repository used by domain tests and local prototypes."""

    def __init__(self):
        self._by_id: dict[UUID, DownloadRequest] = {}
        self._by_key: dict[str, UUID] = {}

    def find_by_idempotency_key(self, key: str) -> DownloadRequest | None:
        """Return a request by key."""
        request_id = self._by_key.get(key)
        return self._by_id.get(request_id) if request_id else None

    def insert(self, request: DownloadRequest) -> DownloadRequest:
        """Insert one request while enforcing key uniqueness."""
        existing = self.find_by_idempotency_key(request.idempotency_key)
        if existing is not None:
            return existing
        self._by_id[request.id] = request
        self._by_key[request.idempotency_key] = request.id
        return request

    def bind_task(self, request_id: UUID, task_instance_id: UUID) -> DownloadRequest:
        """Bind a task to an accepted request."""
        current = self._by_id[request_id]
        updated = replace(current, task_instance_id=task_instance_id, status=DownloadStatus.RUNNING)
        self._by_id[request_id] = updated
        return updated
