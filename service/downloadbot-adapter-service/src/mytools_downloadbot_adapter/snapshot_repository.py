"""旧数据快照的 MySQL 仓储。"""

from __future__ import annotations

from datetime import UTC, datetime
import json
from typing import Callable
from uuid import UUID, uuid4

from .snapshot import (LegacySnapshot, SnapshotItem, SnapshotRejection, SnapshotStatus,
                       canonical_json)


class MySqlSnapshotRepository:
    """使用适配器独立 schema 持久化不可变快照。"""

    def __init__(self, connection_factory: Callable):
        self._connection_factory = connection_factory

    def begin(self, snapshot: LegacySnapshot) -> None:
        """创建捕获中的快照元数据。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO legacy_snapshot "
                    "(id,status,source_schema,source_version,high_water_json,item_count,"
                    "rejection_count,collection_sha256,started_at,sealed_at) "
                    "VALUES (%s,%s,%s,%s,%s,0,0,NULL,%s,NULL)",
                    (str(snapshot.id), snapshot.status.value, snapshot.source_schema,
                     snapshot.source_version, "{}", snapshot.started_at))
            connection.commit()
        finally:
            connection.close()

    def seal(self, snapshot: LegacySnapshot, items: list[SnapshotItem],
             rejections: list[SnapshotRejection]) -> LegacySnapshot:
        """在单个事务中写入条目、拒绝项和封存元数据。"""
        if snapshot.status is not SnapshotStatus.SEALED:
            raise ValueError("snapshot must be sealed")
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                for item in items:
                    cursor.execute(
                        "INSERT INTO legacy_snapshot_item "
                        "(id,snapshot_id,item_type,legacy_id,source_key,payload_json,"
                        "payload_sha256,created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
                        (str(uuid4()), str(snapshot.id), item.item_type, item.legacy_id,
                         item.source_key, canonical_json(item.payload).decode("utf-8"),
                         item.payload_sha256, datetime.now(UTC)))
                for rejection in rejections:
                    cursor.execute(
                        "INSERT INTO legacy_snapshot_rejection "
                        "(id,snapshot_id,item_type,legacy_id,reason_code,detail,created_at) "
                        "VALUES (%s,%s,%s,%s,%s,%s,%s)",
                        (str(uuid4()), str(snapshot.id), rejection.item_type,
                         rejection.legacy_id, rejection.reason_code,
                         rejection.detail[:1024], datetime.now(UTC)))
                cursor.execute(
                    "UPDATE legacy_snapshot SET status=%s,high_water_json=%s,item_count=%s,"
                    "rejection_count=%s,collection_sha256=%s,sealed_at=%s "
                    "WHERE id=%s AND status=%s",
                    (snapshot.status.value,
                     json.dumps(snapshot.high_water, separators=(",", ":")),
                     snapshot.item_count, snapshot.rejection_count,
                     snapshot.collection_sha256, snapshot.sealed_at, str(snapshot.id),
                     SnapshotStatus.CAPTURING.value))
                if cursor.rowcount != 1:
                    raise ValueError("snapshot cannot be sealed from current state")
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
        return snapshot

    def get(self, snapshot_id: UUID) -> LegacySnapshot | None:
        """查询快照元数据。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM legacy_snapshot WHERE id=%s", (str(snapshot_id),))
                row = cursor.fetchone()
            return None if row is None else self._map_snapshot(row)
        finally:
            connection.close()

    def items(self, snapshot_id: UUID) -> list[SnapshotItem]:
        """按稳定顺序返回已封存快照条目。"""
        snapshot = self.get(snapshot_id)
        if snapshot is None or snapshot.status is not SnapshotStatus.SEALED:
            return []
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT item_type,legacy_id,source_key,payload_json "
                    "FROM legacy_snapshot_item WHERE snapshot_id=%s "
                    "ORDER BY item_type,legacy_id", (str(snapshot_id),))
                rows = cursor.fetchall()
            return [SnapshotItem(row["item_type"], row["legacy_id"], row["source_key"],
                                 self._json(row["payload_json"])) for row in rows]
        finally:
            connection.close()

    def export_page(self, snapshot_id: UUID, after_id: str | None, limit: int) -> dict:
        """返回已封存快照的有界迁移页。"""
        if limit < 1 or limit > 500:
            raise ValueError("snapshot page limit is invalid")
        snapshot = self.get(snapshot_id)
        if snapshot is None:
            raise LookupError("snapshot does not exist")
        if snapshot.status is not SnapshotStatus.SEALED:
            raise PermissionError("snapshot is not sealed")
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                item_type = legacy_id = ""
                if after_id:
                    cursor.execute(
                        "SELECT item_type,legacy_id FROM legacy_snapshot_item "
                        "WHERE snapshot_id=%s AND id=%s", (str(snapshot_id), after_id))
                    cursor_row = cursor.fetchone()
                    if cursor_row is None:
                        raise ValueError("snapshot cursor is invalid")
                    item_type = cursor_row["item_type"]
                    legacy_id = cursor_row["legacy_id"]
                cursor.execute(
                    "SELECT id,item_type,legacy_id,source_key,payload_json,payload_sha256 "
                    "FROM legacy_snapshot_item WHERE snapshot_id=%s AND "
                    "(item_type>%s OR (item_type=%s AND legacy_id>%s)) "
                    "ORDER BY item_type,legacy_id LIMIT %s",
                    (str(snapshot_id), item_type, item_type, legacy_id, limit + 1))
                rows = list(cursor.fetchall())
        finally:
            connection.close()
        has_more = len(rows) > limit
        rows = rows[:limit]
        items = [{"snapshotItemId": row["id"], "itemType": row["item_type"],
                  "legacyId": row["legacy_id"], "sourceKey": row["source_key"],
                  "payload": self._json(row["payload_json"]),
                  "payloadSha256": row["payload_sha256"]} for row in rows]
        return {"snapshotId": str(snapshot.id),
                "collectionSha256": snapshot.collection_sha256,
                "itemCount": snapshot.item_count, "items": items,
                "nextAfterId": rows[-1]["id"] if has_more and rows else None}

    def fail(self, snapshot_id: UUID) -> None:
        """将捕获中的快照标记为失败。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("UPDATE legacy_snapshot SET status=%s WHERE id=%s AND status=%s",
                               (SnapshotStatus.FAILED.value, str(snapshot_id),
                                SnapshotStatus.CAPTURING.value))
            connection.commit()
        finally:
            connection.close()

    @staticmethod
    def _map_snapshot(row: dict) -> LegacySnapshot:
        """将数据库行映射为快照模型。"""
        return LegacySnapshot(
            id=UUID(row["id"]), status=SnapshotStatus(row["status"]),
            source_schema=row["source_schema"], source_version=row["source_version"],
            high_water=MySqlSnapshotRepository._json(row["high_water_json"]),
            item_count=int(row["item_count"]), rejection_count=int(row["rejection_count"]),
            collection_sha256=row["collection_sha256"], started_at=row["started_at"],
            sealed_at=row["sealed_at"])

    @staticmethod
    def _json(value: object) -> dict:
        """兼容驱动返回字符串或原生 JSON 对象。"""
        return value if isinstance(value, dict) else json.loads(str(value))
