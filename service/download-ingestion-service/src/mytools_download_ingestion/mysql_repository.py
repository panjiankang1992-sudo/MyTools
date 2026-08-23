"""MySQL persistence adapter for download request aggregates."""

from __future__ import annotations

from datetime import UTC, datetime
import json
from typing import Any, Callable
from uuid import UUID, uuid4

from .models import DownloadRequest, DownloadStatus


class MySqlDownloadRequestRepository:
    """Persist download requests through a PEP 249 connection factory."""

    def __init__(self, connection_factory: Callable[[], Any]):
        self._connection_factory = connection_factory

    def find_by_idempotency_key(self, key: str) -> DownloadRequest | None:
        """Return a request by idempotency key."""
        return self._find("idempotency_key = %s", key)

    def find_by_id(self, request_id: UUID) -> DownloadRequest | None:
        """Return a request by identifier."""
        return self._find("id = %s", str(request_id))

    def insert(self, request: DownloadRequest) -> DownloadRequest:
        """Insert an accepted request or return the concurrent winner."""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """INSERT INTO download_request (
                       id, idempotency_key, source_type, source_key, request_kind, parameters_json,
                       status, task_instance_id, created_at, updated_at
                       ) VALUES (%s, %s, %s, %s, %s, %s, %s, NULL, %s, %s)""",
                    (str(request.id), request.idempotency_key, request.source_type, request.source_key,
                     request.request_kind, json.dumps(request.parameters, separators=(",", ":")),
                     request.status.value, request.created_at, request.updated_at),
                )
            connection.commit()
            return request
        except Exception:
            connection.rollback()
            existing = self.find_by_idempotency_key(request.idempotency_key)
            if existing is None:
                raise
            return existing
        finally:
            connection.close()

    def bind_task(self, request_id: UUID, task_instance_id: UUID) -> DownloadRequest:
        """Idempotently bind the scheduler task and mark the request running."""
        connection = self._connection_factory()
        now = datetime.now(UTC)
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """UPDATE download_request
                       SET task_instance_id = COALESCE(task_instance_id, %s), status = %s, updated_at = %s
                       WHERE id = %s""",
                    (str(task_instance_id), DownloadStatus.RUNNING.value, now, str(request_id)),
                )
            connection.commit()
        finally:
            connection.close()
        bound = self.find_by_id(request_id)
        if bound is None:
            raise KeyError(f"download request does not exist: {request_id}")
        return bound

    def update_status(self, request_id: UUID, status: DownloadStatus) -> DownloadRequest:
        """Persist one reconciled lifecycle status."""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE download_request SET status = %s, updated_at = %s WHERE id = %s",
                    (status.value, datetime.now(UTC), str(request_id)),
                )
            connection.commit()
        finally:
            connection.close()
        updated = self.find_by_id(request_id)
        if updated is None:
            raise KeyError(f"download request does not exist: {request_id}")
        return updated

    def record_result(self, request_id: UUID, result: dict) -> dict:
        """Atomically persist an immutable item result and its pending domain event."""
        connection = self._connection_factory()
        now = datetime.now(UTC)
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """SELECT external_item_id, file_name, content_sha256, size_bytes, storage_uri, asset_id
                       FROM download_item WHERE download_request_id = %s AND external_item_id = %s""",
                    (str(request_id), str(result["itemId"])),
                )
                existing = cursor.fetchone()
                comparable = {
                    "itemId": str(result["itemId"]), "fileName": str(result["fileName"]),
                    "contentSha256": str(result["contentSha256"]), "sizeBytes": int(result["sizeBytes"]),
                    "storageUri": str(result["storageUri"]), "assetId": str(result["assetId"]),
                }
                if existing is not None:
                    persisted = {"itemId": existing["external_item_id"], "fileName": existing["file_name"],
                                 "contentSha256": existing["content_sha256"],
                                 "sizeBytes": int(existing["size_bytes"]), "storageUri": existing["storage_uri"],
                                 "assetId": existing["asset_id"]}
                    if persisted != comparable:
                        raise ValueError("download result idempotency conflict")
                    connection.commit()
                    return persisted
                item_id = str(uuid4())
                cursor.execute(
                    """INSERT INTO download_item (
                       id, download_request_id, source_index, external_item_id, file_name,
                       content_sha256, size_bytes, storage_uri, asset_id, status, created_at, updated_at
                       ) VALUES (%s, %s, 0, %s, %s, %s, %s, %s, %s, 'COMPLETED', %s, %s)""",
                    (item_id, str(request_id), comparable["itemId"], comparable["fileName"],
                     comparable["contentSha256"], comparable["sizeBytes"], comparable["storageUri"],
                     comparable["assetId"], now, now),
                )
                cursor.execute(
                    """INSERT INTO download_outbox
                       (id, aggregate_id, event_type, payload_json, status, created_at, published_at)
                       VALUES (%s, %s, 'DownloadItemCompleted', %s, 'PENDING', %s, NULL)""",
                    (str(uuid4()), str(request_id), json.dumps(comparable, separators=(",", ":")), now),
                )
            connection.commit()
            return comparable
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def list_results(self, request_id: UUID) -> list[dict]:
        """Return stable content summaries without request parameters or physical paths."""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """SELECT external_item_id, file_name, content_sha256, size_bytes,
                              storage_uri, asset_id
                       FROM download_item
                       WHERE download_request_id = %s AND status = 'COMPLETED'
                       ORDER BY external_item_id""",
                    (str(request_id),),
                )
                rows = cursor.fetchall()
            return [{
                "itemId": str(row["external_item_id"]),
                "fileName": str(row["file_name"]),
                "contentSha256": str(row["content_sha256"]),
                "sizeBytes": int(row["size_bytes"]),
                "storageUri": str(row["storage_uri"]),
                "assetId": str(row["asset_id"]),
            } for row in rows]
        finally:
            connection.close()

    def _find(self, predicate: str, value: str) -> DownloadRequest | None:
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"SELECT * FROM download_request WHERE {predicate}", (value,))
                row = cursor.fetchone()
            return None if row is None else self._map(row)
        finally:
            connection.close()

    @staticmethod
    def _map(row: dict[str, Any]) -> DownloadRequest:
        parameters = row["parameters_json"]
        if isinstance(parameters, (str, bytes, bytearray)):
            parameters = json.loads(parameters)
        task_id = row.get("task_instance_id")
        return DownloadRequest(
            id=UUID(str(row["id"])),
            idempotency_key=row["idempotency_key"],
            source_type=row["source_type"],
            source_key=row["source_key"],
            request_kind=row["request_kind"],
            parameters=parameters,
            status=DownloadStatus(row["status"]),
            task_instance_id=None if task_id is None else UUID(str(task_id)),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )
