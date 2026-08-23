"""独立 MsgService 适配器 schema 的 MySQL 仓储。"""

from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
from typing import Callable

from .models import Snapshot
from .repository import StoredSnapshot


class MySqlSnapshotRepository:
    """只访问适配器自有 schema 的快照仓储。"""

    def __init__(self, connection_factory: Callable):
        self._connection_factory = connection_factory

    def find(self, source_system: str, legacy_message_id: str) -> StoredSnapshot | None:
        """按旧身份查询快照。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM legacy_inbound_snapshot "
                               "WHERE source_system=%s AND legacy_message_id=%s",
                               (source_system, legacy_message_id))
                row = cursor.fetchone()
                return None if row is None else self._map(row)
        finally:
            connection.close()

    def insert(self, snapshot: Snapshot, payload_sha256: str) -> StoredSnapshot:
        """插入快照并依赖唯一键处理并发重放。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT IGNORE INTO legacy_inbound_snapshot "
                    "(source_system,legacy_message_id,payload_sha256,payload_json,captured_at) "
                    "VALUES (%s,%s,%s,%s,%s)",
                    (snapshot.source_system, snapshot.legacy_message_id, payload_sha256,
                     json.dumps(snapshot.document(), separators=(",", ":"), ensure_ascii=False),
                     datetime.now(UTC)))
            connection.commit()
        finally:
            connection.close()
        stored = self.find(snapshot.source_system, snapshot.legacy_message_id)
        if stored is None:
            raise RuntimeError("snapshot insert did not persist")
        return stored

    def high_water(self) -> int:
        """读取当前追加日志高水位。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT COALESCE(MAX(sequence_id),0) AS high_water "
                               "FROM legacy_inbound_snapshot")
                return int(cursor.fetchone()["high_water"])
        finally:
            connection.close()

    def evidence(self, high_water: int) -> tuple[int, str]:
        """只计算一次冻结高水位证据，后续分页读取不可变缓存。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT item_count,collection_sha256 "
                               "FROM legacy_inbound_export_snapshot WHERE high_water_sequence=%s",
                               (high_water,))
                existing = cursor.fetchone()
                if existing is not None:
                    return int(existing["item_count"]), str(existing["collection_sha256"])
                cursor.execute("SELECT source_system,legacy_message_id,payload_sha256 "
                               "FROM legacy_inbound_snapshot WHERE sequence_id<=%s "
                               "ORDER BY sequence_id ASC", (high_water,))
                rows = cursor.fetchall()
                digest = hashlib.sha256()
                for row in rows:
                    for value in (row["source_system"], row["legacy_message_id"],
                                  row["payload_sha256"]):
                        encoded = str(value).encode("utf-8")
                        digest.update(len(encoded).to_bytes(4, "big"))
                        digest.update(encoded)
                value = digest.hexdigest()
                cursor.execute("INSERT IGNORE INTO legacy_inbound_export_snapshot "
                               "(high_water_sequence,item_count,collection_sha256) VALUES (%s,%s,%s)",
                               (high_water, len(rows), value))
            connection.commit()
        finally:
            connection.close()
        return self.evidence(high_water)

    def page(self, after_sequence: int, high_water: int, limit: int) -> list[StoredSnapshot]:
        """按自增序号读取稳定页。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM legacy_inbound_snapshot "
                               "WHERE sequence_id>%s AND sequence_id<=%s "
                               "ORDER BY sequence_id ASC LIMIT %s",
                               (after_sequence, high_water, limit))
                return [self._map(row) for row in cursor.fetchall()]
        finally:
            connection.close()

    @staticmethod
    def _map(row: dict) -> StoredSnapshot:
        """将数据库行映射为快照。"""
        document = row["payload_json"]
        if isinstance(document, (str, bytes, bytearray)):
            document = json.loads(document)
        return StoredSnapshot(int(row["sequence_id"]), Snapshot.from_document(document),
                              str(row["payload_sha256"]))
