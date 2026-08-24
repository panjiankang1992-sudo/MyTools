"""适配器 MySQL 收件箱仓储。"""

from __future__ import annotations

from datetime import UTC, datetime
import json
from typing import Callable
from uuid import UUID

from .models import AcceptLegacyEvent, EventStatus, LegacyEvent


class MySqlEventRepository:
    """使用独立 schema 持久化旧事件。"""

    def __init__(self, connection_factory: Callable):
        self._connection_factory = connection_factory

    def find_by_event_id(self, event_id: str) -> LegacyEvent | None:
        """按旧事件标识查询。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM adapter_event WHERE event_id = %s", (event_id,))
                row = cursor.fetchone()
                return None if row is None else self._map(row)
        finally:
            connection.close()

    def insert(self, event: LegacyEvent) -> LegacyEvent:
        """插入事件并依赖唯一键抵御并发重复。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT IGNORE INTO adapter_event "
                    "(id,event_id,source_type,source_key,request_kind,parameters_json,status,"
                    "download_request_id,error_code,created_at,updated_at) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (str(event.id), event.event_id, event.source_type, event.source_key,
                     event.request_kind, json.dumps(event.parameters, separators=(",", ":")),
                     event.status.value, None, None, event.created_at, event.updated_at))
            connection.commit()
        finally:
            connection.close()
        return self.find_by_event_id(event.event_id) or event

    def update(self, event: LegacyEvent) -> LegacyEvent:
        """更新转发状态。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE adapter_event SET status=%s,download_request_id=%s,error_code=%s,"
                    "updated_at=%s WHERE event_id=%s",
                    (event.status.value,
                     None if event.download_request_id is None else str(event.download_request_id),
                     event.error_code, datetime.now(UTC), event.event_id))
            connection.commit()
        finally:
            connection.close()
        return self.find_by_event_id(event.event_id) or event

    def retryable(self, limit: int) -> list[AcceptLegacyEvent]:
        """按稳定顺序返回尚未成功转发的事件。"""
        if limit < 1 or limit > 500:
            raise ValueError("retryable event limit is invalid")
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT event_id,source_type,source_key,request_kind,parameters_json "
                    "FROM adapter_event WHERE status IN ('RECEIVED','FAILED') "
                    "ORDER BY created_at,event_id LIMIT %s", (limit,))
                rows = cursor.fetchall()
            return [AcceptLegacyEvent(
                event_id=str(row["event_id"]), source_type=str(row["source_type"]),
                source_key=str(row["source_key"]), request_kind=str(row["request_kind"]),
                parameters=(row["parameters_json"] if isinstance(row["parameters_json"], dict)
                            else json.loads(str(row["parameters_json"])))) for row in rows]
        finally:
            connection.close()

    @staticmethod
    def _map(row: dict) -> LegacyEvent:
        """将数据库行转换为领域模型。"""
        return LegacyEvent(
            id=UUID(row["id"]), event_id=row["event_id"], source_type=row["source_type"],
            source_key=row["source_key"], request_kind=row["request_kind"],
            parameters=json.loads(row["parameters_json"]), status=EventStatus(row["status"]),
            download_request_id=(None if row["download_request_id"] is None
                                 else UUID(row["download_request_id"])),
            error_code=row["error_code"], created_at=row["created_at"], updated_at=row["updated_at"])
