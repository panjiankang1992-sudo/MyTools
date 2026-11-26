"""MySQL persistence adapter for download request aggregates."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import json
from typing import Any, Callable
from uuid import UUID, uuid4

from .models import DownloadRequest, DownloadStatus

_TERMINAL_STATUSES = frozenset({
    DownloadStatus.CANCELLED,
    DownloadStatus.SUCCEEDED,
    DownloadStatus.FAILED,
})


class MySqlDownloadRequestRepository:
    """Persist download requests through a PEP 249 connection factory."""

    def __init__(self, connection_factory: Callable[[], Any], *, background_enabled: bool = False):
        self._connection_factory = connection_factory
        self.background_enabled = background_enabled

    def find_by_idempotency_key(self, key: str) -> DownloadRequest | None:
        """Return a request by idempotency key."""
        return self._find("idempotency_key = %s", key)

    def find_by_id(self, request_id: UUID) -> DownloadRequest | None:
        """Return a request by identifier."""
        return self._find("id = %s", str(request_id))

    def unfinished_app_request_ids(self, owner_id: int) -> list[str]:
        """只读取指定所有者的未完成 APP 下载请求标识。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """SELECT id FROM download_request
                       WHERE owner_id = %s AND source_type = %s AND status IN (%s,%s,%s,%s)""",
                    (owner_id, "APP_TOOL", DownloadStatus.ACCEPTED, DownloadStatus.PLANNING,
                     DownloadStatus.RUNNING, DownloadStatus.CANCELLING),
                )
                return [str(row["id"]) for row in cursor.fetchall()]
        finally:
            connection.close()

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
                if self.background_enabled:
                    # 与请求插入同事务，进程崩溃后仍能继续对账与渠道状态同步。
                    self._insert_background(cursor, "request:" + str(request.id), "RECONCILE", request.id,
                                            None, {}, request.created_at)
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
        """幂等绑定 Scheduler 任务，并禁止迟到绑定回退已推进的状态。"""
        connection = self._connection_factory()
        now = datetime.now(UTC)
        try:
            with connection.cursor() as cursor:
                # 与状态推进锁定同一请求行，避免并发 create 的迟到 bind 回退终态。
                cursor.execute(
                    "SELECT task_instance_id, status FROM download_request WHERE id = %s FOR UPDATE",
                    (str(request_id),),
                )
                row = cursor.fetchone()
                if row is None:
                    raise KeyError(f"download request does not exist: {request_id}")
                existing_task_id = row["task_instance_id"]
                if existing_task_id is not None and str(existing_task_id) != str(task_instance_id):
                    raise ValueError("download request task binding conflict")
                current_status = DownloadStatus(str(row["status"]))
                if existing_task_id is None:
                    status = (current_status if current_status in _TERMINAL_STATUSES
                              or current_status == DownloadStatus.CANCELLING
                              else DownloadStatus.RUNNING)
                    cursor.execute(
                        """UPDATE download_request
                           SET task_instance_id = %s, status = %s, updated_at = %s
                           WHERE id = %s""",
                        (str(task_instance_id), status.value, now, str(request_id)),
                    )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
        bound = self.find_by_id(request_id)
        if bound is None:
            raise KeyError(f"download request does not exist: {request_id}")
        return bound

    def update_status(self, request_id: UUID, status: DownloadStatus) -> DownloadRequest:
        """持久化非终态生命周期，并在并发时禁止覆盖已提交的终态。"""
        if status in _TERMINAL_STATUSES:
            return self.complete_terminal(request_id, status)
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                # 状态推进和终态封口锁定同一行，避免迟到的 CANCELLING 覆盖成功终态。
                cursor.execute(
                    "SELECT status FROM download_request WHERE id = %s FOR UPDATE",
                    (str(request_id),),
                )
                row = cursor.fetchone()
                if row is None:
                    raise KeyError(f"download request does not exist: {request_id}")
                current_status = DownloadStatus(str(row["status"]))
                regresses_cancelling = (current_status == DownloadStatus.CANCELLING
                                        and status in {DownloadStatus.ACCEPTED,
                                                       DownloadStatus.PLANNING,
                                                       DownloadStatus.RUNNING})
                if current_status not in _TERMINAL_STATUSES and not regresses_cancelling:
                    cursor.execute(
                        "UPDATE download_request SET status = %s, updated_at = %s WHERE id = %s",
                        (status.value, datetime.now(UTC), str(request_id)),
                    )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
        updated = self.find_by_id(request_id)
        if updated is None:
            raise KeyError(f"download request does not exist: {request_id}")
        return updated

    def complete_terminal(self, request_id: UUID, status: DownloadStatus) -> DownloadRequest:
        """原子写入请求终态，并把全部待处理标签收敛为失败终态。"""
        if status not in _TERMINAL_STATUSES:
            raise ValueError("download request status is not terminal")
        connection = self._connection_factory()
        now = datetime.now(UTC)
        try:
            with connection.cursor() as cursor:
                # 请求行锁与结果写入使用相同锁顺序，避免终态封口后出现新的 PENDING。
                cursor.execute(
                    "SELECT status FROM download_request WHERE id = %s FOR UPDATE",
                    (str(request_id),),
                )
                row = cursor.fetchone()
                if row is None:
                    raise KeyError(f"download request does not exist: {request_id}")
                cursor.execute(
                    """UPDATE download_item
                       SET tag_status = %s, tags_json = %s, updated_at = %s
                       WHERE download_request_id = %s AND status = 'COMPLETED' AND tag_status = %s
                         AND NOT EXISTS (SELECT 1 FROM download_background_job bg
                             WHERE bg.download_request_id = download_item.download_request_id
                               AND bg.external_item_id = download_item.external_item_id
                               AND bg.kind = 'ENRICHMENT')""" if self.background_enabled else
                    """UPDATE download_item SET tag_status = %s, tags_json = %s, updated_at = %s
                       WHERE download_request_id = %s AND status = 'COMPLETED' AND tag_status = %s""",
                    ("FAILED", "[]", now, str(request_id), "PENDING"),
                )
                current_status = DownloadStatus(str(row["status"]))
                if current_status not in _TERMINAL_STATUSES:
                    cursor.execute(
                        "UPDATE download_request SET status = %s, updated_at = %s WHERE id = %s",
                        (status.value, now, str(request_id)),
                    )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
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
                # 与终态封口事务先锁同一请求行，迟到结果只能直接进入失败标签终态。
                cursor.execute(
                    "SELECT status, owner_id FROM download_request WHERE id = %s FOR UPDATE",
                    (str(request_id),),
                )
                request_row = cursor.fetchone()
                if request_row is None:
                    raise KeyError("download request does not exist")
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
                request_status = DownloadStatus(str(request_row["status"]))
                tag_status = "FAILED" if request_status in _TERMINAL_STATUSES else "PENDING"
                cursor.execute(
                    """INSERT INTO download_item (
                       id, download_request_id, source_index, external_item_id, file_name,
                       content_sha256, size_bytes, storage_uri, asset_id, status,
                       tag_status, tags_json, created_at, updated_at
                       ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'COMPLETED', %s, %s, %s, %s)""",
                    (item_id, str(request_id), comparable["sourceIndex"], comparable["itemId"], comparable["fileName"],
                     comparable["contentSha256"], comparable["sizeBytes"], comparable["storageUri"],
                     comparable["assetId"], tag_status, "[]", now, now),
                )
                if result.get("enrichmentMode") == "ASYNC" and request_status not in _TERMINAL_STATUSES:
                    if not self.background_enabled:
                        raise ValueError("asynchronous download processing is disabled")
                    # 仅使用已登记资产、请求所有者和摘要构造任务，不持久化临时物理路径。
                    payload = {"downloadRequestId": str(request_id), "itemId": comparable["itemId"],
                               "ownerId": int(request_row["owner_id"]), "assetRegistryId": comparable["assetId"],
                               "assetId": comparable["assetId"], "contentSha256": comparable["contentSha256"],
                               "filename": comparable["fileName"], "mimeType": str(result["mimeType"]),
                               "mediaRegistered": bool(result.get("mediaRegistered"))}
                    self._insert_background(cursor, "enrich:" + item_id, "ENRICHMENT", request_id,
                                            comparable["itemId"], payload, now)
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

    @staticmethod
    def _insert_background(cursor, identifier, kind, request_id, item_id, payload, now):
        """在业务事务内创建幂等后台工作，不把结果落库与入队拆成两个提交。"""
        cursor.execute("""INSERT INTO download_background_job
            (id,kind,download_request_id,external_item_id,payload_json,state,next_attempt_at,created_at,updated_at)
            VALUES (%s,%s,%s,%s,%s,'PENDING',%s,%s,%s)""",
            (identifier, kind, str(request_id), item_id, json.dumps(payload, separators=(',', ':')), now, now, now))

    def claim_background(self, kind: str, limit: int = 10) -> list[dict]:
        """短事务领取到期工作，过期租约可恢复，多实例不会阻塞彼此。"""
        connection = self._connection_factory()
        now = datetime.now(UTC)
        try:
            with connection.cursor() as cursor:
                cursor.execute("""SELECT * FROM download_background_job
                    WHERE kind=%s AND state IN ('PENDING','SUBMITTED') AND next_attempt_at<=%s
                      AND (claim_until IS NULL OR claim_until<=%s)
                    ORDER BY next_attempt_at,id LIMIT %s FOR UPDATE SKIP LOCKED""",
                    (kind, now, now, max(1, min(limit, 25))))
                jobs = cursor.fetchall()
                for job in jobs:
                    token = str(uuid4())
                    cursor.execute("""UPDATE download_background_job SET claim_token=%s,claim_until=%s,
                        updated_at=%s WHERE id=%s""", (token, now + timedelta(seconds=120), now, job['id']))
                    job['claim_token'] = token
                    if isinstance(job['payload_json'], str):
                        job['payload_json'] = json.loads(job['payload_json'])
            connection.commit()
            return jobs
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def finish_background(self, job: dict, state: str, *, task_id: str | None = None,
                          error: str | None = None, delay: int = 2,
                          fail_unbound_request: bool = False) -> None:
        """按租约回写后台状态，后处理失败不回退下载成功状态。"""
        if state not in {'PENDING', 'SUBMITTED', 'SUCCEEDED', 'FAILED'}:
            raise ValueError('background state is invalid')
        if fail_unbound_request and (job['kind'], state) != ('RECONCILE', 'FAILED'):
            raise ValueError('only rejected reconciliation can fail an unbound request')
        connection = self._connection_factory()
        now = datetime.now(UTC)
        try:
            with connection.cursor() as cursor:
                request_row = None
                if fail_unbound_request:
                    cursor.execute('SELECT status,task_instance_id FROM download_request WHERE id=%s FOR UPDATE',
                                   (job['download_request_id'],))
                    request_row = cursor.fetchone()
                if job['kind'] == 'ENRICHMENT' and state in {'SUCCEEDED', 'FAILED'}:
                    # 与结果回写和下载终态使用同一请求优先锁序，避免后台与用户读取交叉死锁。
                    cursor.execute('SELECT status FROM download_request WHERE id=%s FOR UPDATE',
                                   (job['download_request_id'],))
                    cursor.fetchone()
                cursor.execute('SELECT claim_token FROM download_background_job WHERE id=%s FOR UPDATE',
                               (job['id'],))
                row = cursor.fetchone()
                if row is None or row['claim_token'] != job['claim_token']:
                    connection.rollback()
                    return
                if request_row is not None:
                    # 并发提交若已绑定，保留对账任务。只结束仍未绑定且未执行的请求。
                    if request_row['task_instance_id'] is not None:
                        state, error = 'PENDING', None
                    elif request_row['status'] in {'ACCEPTED', 'PLANNING'}:
                        cursor.execute("""UPDATE download_request SET status='FAILED',updated_at=%s
                            WHERE id=%s""", (now, job['download_request_id']))
                if job['kind'] == 'ENRICHMENT' and state in {'SUCCEEDED', 'FAILED'}:
                    # 任务终止且没有标签回调时显式结束后处理，绝不留下永久 PENDING。
                    cursor.execute("""UPDATE download_item SET tag_status='FAILED',tags_json='[]',updated_at=%s
                        WHERE download_request_id=%s AND external_item_id=%s AND tag_status='PENDING'""",
                        (now, job['download_request_id'], job['external_item_id']))
                    cursor.execute('SELECT tag_status FROM download_item WHERE download_request_id=%s AND external_item_id=%s',
                                   (job['download_request_id'], job['external_item_id']))
                    item = cursor.fetchone()
                    if item and item['tag_status'] == 'FAILED':
                        state = 'FAILED'
                cursor.execute("""UPDATE download_background_job SET state=%s,
                    task_instance_id=COALESCE(%s,task_instance_id),attempts=attempts+%s,next_attempt_at=%s,
                    last_error_code=%s,claim_token=NULL,claim_until=NULL,updated_at=%s
                    WHERE id=%s AND claim_token=%s""",
                    (state, task_id, int(error is not None), now + timedelta(seconds=max(1, min(delay, 300))),
                     error, now, job['id'], job['claim_token']))
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def background_summary(self, request_id: UUID) -> dict:
        """下载与后处理独立展示，标签失败不会把已保存文件改成下载失败。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT state,COUNT(*) n FROM download_background_job "
                               "WHERE download_request_id=%s AND kind='ENRICHMENT' GROUP BY state", (str(request_id),))
                counts = {row['state']: int(row['n']) for row in cursor.fetchall()}
            pending = counts.get('PENDING', 0) + counts.get('SUBMITTED', 0)
            failed = counts.get('FAILED', 0)
            status = 'PENDING' if pending else 'FAILED' if failed else 'SUCCEEDED' if counts else 'NOT_REQUIRED'
            return {'postProcessingStatus': status, 'postProcessingPending': pending,
                    'postProcessingFailed': failed}
        finally:
            connection.close()
