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
                       id, owner_id, idempotency_key, source_type, source_key, request_kind, parameters_json,
                       status, task_instance_id, created_at, updated_at
                       ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, NULL, %s, %s)""",
                    (str(request.id), request.owner_id, request.idempotency_key,
                     request.source_type, request.source_key,
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
                    """SELECT source_index, external_item_id, file_name, content_sha256, size_bytes, storage_uri, asset_id
                       FROM download_item WHERE download_request_id = %s AND external_item_id = %s""",
                    (str(request_id), str(result["itemId"])),
                )
                existing = cursor.fetchone()
                comparable = {
                    "sourceIndex": int(result.get("sourceIndex", 0)),
                    "itemId": str(result["itemId"]), "fileName": str(result["fileName"]),
                    "contentSha256": str(result["contentSha256"]), "sizeBytes": int(result["sizeBytes"]),
                    "storageUri": str(result["storageUri"]), "assetId": str(result["assetId"]),
                }
                if existing is not None:
                    persisted = {"sourceIndex": int(existing["source_index"]),
                                 "itemId": existing["external_item_id"], "fileName": existing["file_name"],
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
                       ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'COMPLETED', %s, %s)""",
                    (item_id, str(request_id), comparable["sourceIndex"], comparable["itemId"], comparable["fileName"],
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
                              storage_uri, asset_id, tag_status, tags_json
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
                "tagStatus": str(row["tag_status"]),
                "tags": json.loads(row["tags_json"]) if isinstance(row["tags_json"], str)
                else row["tags_json"],
            } for row in rows]
        finally:
            connection.close()

    def record_progress(self, request_id: UUID, progress: dict) -> dict:
        """Persist one monotonic progress milestone without allowing total-size drift."""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO download_progress(download_request_id,external_item_id,downloaded_bytes,
                        total_bytes,progress_percent,updated_at) VALUES (%s,%s,%s,%s,%s,%s)
                    ON DUPLICATE KEY UPDATE
                        downloaded_bytes=IF(total_bytes=VALUES(total_bytes),
                            GREATEST(downloaded_bytes,VALUES(downloaded_bytes)),downloaded_bytes),
                        progress_percent=IF(total_bytes=VALUES(total_bytes),
                            GREATEST(progress_percent,VALUES(progress_percent)),progress_percent),
                        updated_at=IF(total_bytes=VALUES(total_bytes),VALUES(updated_at),updated_at)
                    """, (str(request_id), progress["itemId"], progress["downloadedBytes"],
                          progress["totalBytes"], progress["progressPercent"], datetime.now(UTC)))
                cursor.execute("SELECT downloaded_bytes,total_bytes,progress_percent FROM download_progress "
                               "WHERE download_request_id=%s AND external_item_id=%s",
                               (str(request_id), progress["itemId"]))
                row = cursor.fetchone()
            connection.commit()
            if int(row["total_bytes"]) != progress["totalBytes"]:
                raise ValueError("download progress idempotency conflict")
            return {"itemId": progress["itemId"], "downloadedBytes": int(row["downloaded_bytes"]),
                    "totalBytes": int(row["total_bytes"]),
                    "progressPercent": int(row["progress_percent"])}
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def progress_summary(self, request_id: UUID) -> dict:
        """Return aggregate byte progress across active request items."""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT COALESCE(SUM(downloaded_bytes),0) downloaded_bytes,"
                               "COALESCE(SUM(total_bytes),0) total_bytes FROM download_progress "
                               "WHERE download_request_id=%s", (str(request_id),))
                row = cursor.fetchone()
            total = int(row["total_bytes"])
            downloaded = int(row["downloaded_bytes"])
            return {"progressDownloadedBytes": downloaded, "progressTotalBytes": total,
                    "progressPercent": 0 if total == 0 else downloaded * 100 // total}
        finally:
            connection.close()

    def record_tags(self, request_id: UUID, result: dict) -> dict:
        """Idempotently persist a terminal tagging result for an existing item."""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT tag_status,tags_json FROM download_item "
                    "WHERE download_request_id=%s AND external_item_id=%s FOR UPDATE",
                    (str(request_id), str(result["itemId"])))
                existing = cursor.fetchone()
                if existing is None:
                    raise KeyError("download item does not exist")
                tags = existing["tags_json"]
                if isinstance(tags, (str, bytes, bytearray)):
                    tags = json.loads(tags)
                current = {"itemId": str(result["itemId"]),
                           "tagStatus": str(existing["tag_status"]), "tags": tags}
                if current["tagStatus"] != "PENDING" and current != result:
                    raise ValueError("download tag result idempotency conflict")
                if current["tagStatus"] == "PENDING":
                    cursor.execute(
                        "UPDATE download_item SET tag_status=%s,tags_json=%s,updated_at=%s "
                        "WHERE download_request_id=%s AND external_item_id=%s",
                        (result["tagStatus"], json.dumps(result["tags"], ensure_ascii=False,
                                                         separators=(",", ":")), datetime.now(UTC),
                         str(request_id), str(result["itemId"])))
            connection.commit()
            return dict(result)
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def find_digest(self, source_system: str, item_type: str, legacy_id: str) -> str | None:
        """查询已经导入的 DownloadBot 历史摘要。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT payload_sha256 FROM legacy_download_history "
                    "WHERE source_system=%s AND item_type=%s AND legacy_id=%s",
                    (source_system, item_type, legacy_id))
                row = cursor.fetchone()
            return None if row is None else str(row["payload_sha256"])
        finally:
            connection.close()

    def insert_history(self, migration_key: str, source_system: str, item: dict) -> None:
        """写入一条不可变的旧下载历史。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO legacy_download_history "
                    "(id,source_system,item_type,legacy_id,source_key,payload_json,payload_sha256,"
                    "first_migration_key,created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (str(uuid4()), source_system, item["itemType"], item["legacyId"],
                     item["sourceKey"], json.dumps(item["payload"], ensure_ascii=False,
                                                   separators=(",", ":")),
                     item["payloadSha256"], migration_key, datetime.now(UTC)))
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def record_rejection(self, migration_key: str, source_system: str,
                         item: dict, reason_code: str) -> None:
        """幂等记录一条历史迁移拒绝。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT IGNORE INTO legacy_download_migration_rejection "
                    "(id,migration_key,source_system,item_type,legacy_id,reason_code,created_at) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s)",
                    (str(uuid4()), migration_key, source_system,
                     str(item.get("itemType") or "INVALID")[:32],
                     str(item.get("legacyId") or "INVALID")[:255], reason_code,
                     datetime.now(UTC)))
            connection.commit()
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
            owner_id=int(row.get("owner_id") or 0),
            task_instance_id=None if task_id is None else UUID(str(task_id)),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )
