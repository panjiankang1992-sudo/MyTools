"""已封存旧资产快照的只读仓储。"""

from __future__ import annotations

import json
from typing import Callable


class SnapshotRepository:
    """只读取适配器自有 schema 中的 SEALED 快照。"""

    def __init__(self, connection_factory: Callable):
        self._connection_factory = connection_factory

    def page(self, snapshot_id: str, after_sequence: int, limit: int) -> list[dict]:
        """按稳定序号读取一页已封存资产。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT status FROM legacy_asset_snapshot WHERE snapshot_id=%s",
                               (snapshot_id,))
                snapshot = cursor.fetchone()
                if snapshot is None:
                    raise LookupError("legacy asset snapshot does not exist")
                if snapshot["status"] != "SEALED":
                    raise ValueError("legacy asset snapshot is not sealed")
                cursor.execute(
                    "SELECT sequence_id,payload_json FROM legacy_asset_snapshot_item "
                    "WHERE snapshot_id=%s AND sequence_id>%s ORDER BY sequence_id LIMIT %s",
                    (snapshot_id, after_sequence, limit))
                result = []
                for row in cursor.fetchall():
                    payload = row["payload_json"]
                    if isinstance(payload, (str, bytes, bytearray)):
                        payload = json.loads(payload)
                    result.append({"sequenceId": int(row["sequence_id"]), "payload": payload})
                return result
        finally:
            connection.close()
