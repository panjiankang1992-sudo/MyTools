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

    def find_by_id(self, request_id: UUID) -> DownloadRequest | None:
        """Return a request by its business identifier."""

    def insert(self, request: DownloadRequest) -> DownloadRequest:
        """Persist a newly accepted request."""

    def bind_task(self, request_id: UUID, task_instance_id: UUID) -> DownloadRequest:
        """Bind the scheduler parent task and move the request to running."""

    def update_status(self, request_id: UUID, status: DownloadStatus) -> DownloadRequest:
        """Update one request lifecycle status."""


class TaskScheduler(Protocol):
    """Task Scheduler operations used by download orchestration."""

    def create_task(self, *, task_name: str, idempotency_key: str,
                    business_id: str, parameters: dict) -> UUID:
        """Idempotently create a scheduler task and return its instance ID."""

    def get_task(self, task_id: UUID) -> dict:
        """Return one scheduler task representation."""

    def cancel_task(self, task_id: UUID) -> dict:
        """Request cancellation of one scheduler task."""


class DownloadRequestService:
    """Accept download requests and bind each request to one parent task."""

    def __init__(self, repository: DownloadRequestRepository, scheduler: TaskScheduler):
        self._repository = repository
        self._scheduler = scheduler

    def create(self, command: CreateDownloadRequest) -> DownloadRequest:
        """Idempotently create a request and its scheduler task binding."""
        existing = self._repository.find_by_idempotency_key(command.idempotency_key)
        if existing is not None and not equivalent(existing, command):
            raise ValueError("download request idempotency conflict")
        if existing is not None and existing.task_instance_id is not None:
            return existing
        task_name = TASK_NAMES.get(command.request_kind)
        if task_name is None:
            raise ValueError(f"unsupported download request kind: {command.request_kind}")
        accepted = existing or self._repository.insert(DownloadRequest.accept(command))
        if accepted.task_instance_id is not None:
            return accepted
        task_id = self._scheduler.create_task(
            task_name=task_name,
            idempotency_key=f"download:{accepted.idempotency_key}",
            business_id=str(accepted.id),
            parameters={"downloadRequestId": str(accepted.id), **accepted.parameters},
        )
        return self._repository.bind_task(accepted.id, task_id)

    def get(self, request_id: UUID) -> DownloadRequest | None:
        """Return a request after reconciling its scheduler lifecycle state."""
        current = self._repository.find_by_id(request_id)
        if current is None or current.task_instance_id is None:
            return current
        scheduler_task = self._scheduler.get_task(current.task_instance_id)
        status = transition(current.status, scheduler_status(str(scheduler_task["status"])))
        return current if status == current.status else self._repository.update_status(current.id, status)

    def cancel(self, request_id: UUID) -> DownloadRequest | None:
        """Cancel the bound scheduler task and reconcile the returned state."""
        current = self._repository.find_by_id(request_id)
        if current is None or current.task_instance_id is None:
            return current
        if current.status in {DownloadStatus.CANCELLED, DownloadStatus.SUCCEEDED, DownloadStatus.FAILED}:
            return current
        scheduler_task = self._scheduler.cancel_task(current.task_instance_id)
        return self._repository.update_status(
            current.id, transition(current.status, scheduler_status(str(scheduler_task["status"]))))


def scheduler_status(value: str) -> DownloadStatus:
    """Map scheduler lifecycle states to the download aggregate lifecycle."""
    mapping = {
        "CREATED": DownloadStatus.PLANNING,
        "QUEUED": DownloadStatus.PLANNING,
        "DISPATCHING": DownloadStatus.RUNNING,
        "RUNNING": DownloadStatus.RUNNING,
        "CANCELLING": DownloadStatus.CANCELLING,
        "CANCELLED": DownloadStatus.CANCELLED,
        "SUCCEEDED": DownloadStatus.SUCCEEDED,
        "FAILED": DownloadStatus.FAILED,
        "TIMED_OUT": DownloadStatus.FAILED,
    }
    try:
        return mapping[value]
    except KeyError as exception:
        raise ValueError(f"unsupported scheduler status: {value}") from exception


def equivalent(existing: DownloadRequest, command: CreateDownloadRequest) -> bool:
    """Require a replayed idempotency key to carry the exact same business request."""
    return (existing.source_type == command.source_type
            and existing.source_key == command.source_key
            and existing.request_kind == command.request_kind
            and existing.parameters == command.parameters)


def transition(current: DownloadStatus, proposed: DownloadStatus) -> DownloadStatus:
    """Prevent scheduler reconciliation from regressing terminal or cancelling requests."""
    terminal = {DownloadStatus.CANCELLED, DownloadStatus.SUCCEEDED, DownloadStatus.FAILED}
    if current in terminal:
        return current
    if current == DownloadStatus.CANCELLING and proposed in {
            DownloadStatus.ACCEPTED, DownloadStatus.PLANNING, DownloadStatus.RUNNING}:
        return current
    return proposed


class InMemoryDownloadRequestRepository:
    """Deterministic repository used by domain tests and local prototypes."""

    def __init__(self):
        self._by_id: dict[UUID, DownloadRequest] = {}
        self._by_key: dict[str, UUID] = {}

    def find_by_idempotency_key(self, key: str) -> DownloadRequest | None:
        """Return a request by key."""
        request_id = self._by_key.get(key)
        return self._by_id.get(request_id) if request_id else None

    def find_by_id(self, request_id: UUID) -> DownloadRequest | None:
        """Return a request by identifier."""
        return self._by_id.get(request_id)

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

    def update_status(self, request_id: UUID, status: DownloadStatus) -> DownloadRequest:
        """Update one in-memory aggregate status."""
        current = self._by_id[request_id]
        updated = replace(current, status=status)
        self._by_id[request_id] = updated
        return updated
