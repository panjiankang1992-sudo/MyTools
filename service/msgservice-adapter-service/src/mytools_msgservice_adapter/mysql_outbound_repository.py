"""独立适配器 schema 的历史发件 MySQL 仓储。"""

from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
from typing import Callable

from .models import OutboundSnapshot
from .outbound_repository import StoredOutboundSnapshot

EVIDENCE_PROTOCOL = "messaging-outbound-history-v1"


class MySqlOutboundSnapshotRepository:
    """只访问适配器自有发件快照表。"""

    def __init__(self, connection_factory: Callable):
        self._connection_factory = connection_factory

    def find(self, source_system: str, legacy_message_id: str) -> StoredOutboundSnapshot | None:
        """按旧身份查询快照。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM legacy_outbound_snapshot "
                               "WHERE source_system=%s AND legacy_message_id=%s",
                               (source_system, legacy_message_id))
                row = cursor.fetchone()
                return None if row is None else self._map(row)
        finally:
            connection.close()

    def insert(self, snapshot: OutboundSnapshot, payload_sha256: str) -> StoredOutboundSnapshot:
        """插入快照并依赖唯一键处理并发重放。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("INSERT IGNORE INTO legacy_outbound_snapshot "
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
            raise RuntimeError("outbound snapshot insert did not persist")
        return stored

    def high_water(self) -> int:
        """读取当前追加日志高水位。"""
        return self._scalar("SELECT COALESCE(MAX(sequence_id),0) AS value FROM legacy_outbound_snapshot")

    def evidence(self, high_water: int) -> tuple[int, str]:
        """计算并缓存冻结高水位证据。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT item_count,collection_sha256 FROM legacy_outbound_export_snapshot "
                               "WHERE high_water_sequence=%s AND protocol_version=%s",
                               (high_water, EVIDENCE_PROTOCOL))
                existing = cursor.fetchone()
                if existing is not None:
                    return int(existing["item_count"]), str(existing["collection_sha256"])
                cursor.execute("SELECT * FROM legacy_outbound_snapshot WHERE sequence_id<=%s "
                               "ORDER BY source_system,legacy_message_id", (high_water,))
                rows = cursor.fetchall()
                digest = hashlib.sha256()
                for row in rows:
                    item = self._map(row)
                    for value in (item.snapshot.source_system, item.snapshot.legacy_message_id,
                                  item.snapshot.migration_digest()):
                        encoded = value.encode("utf-8")
                        digest.update(len(encoded).to_bytes(4, "big"))
                        digest.update(encoded)
                value = digest.hexdigest()
                cursor.execute("INSERT IGNORE INTO legacy_outbound_export_snapshot "
                               "(high_water_sequence,protocol_version,item_count,collection_sha256) "
                               "VALUES (%s,%s,%s,%s)", (high_water, EVIDENCE_PROTOCOL, len(rows), value))
            connection.commit()
        finally:
            connection.close()
        return self.evidence(high_water)

    def page(self, after_sequence: int, high_water: int,
             limit: int) -> list[StoredOutboundSnapshot]:
        """按稳定序号读取一页。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM legacy_outbound_snapshot WHERE sequence_id>%s "
                               "AND sequence_id<=%s ORDER BY sequence_id LIMIT %s",
                               (after_sequence, high_water, limit))
                return [self._map(row) for row in cursor.fetchall()]
        finally:
            connection.close()

    def _scalar(self, query: str) -> int:
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(query)
                return int(cursor.fetchone()["value"])
        finally:
            connection.close()

    @staticmethod
    def _map(row: dict) -> StoredOutboundSnapshot:
        document = row["payload_json"]
        if isinstance(document, (str, bytes, bytearray)):
            document = json.loads(document)
        return StoredOutboundSnapshot(int(row["sequence_id"]),
                                      OutboundSnapshot.from_document(document),
                                      str(row["payload_sha256"]))
