"""旧库一致性快照捕获编排。"""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime
from typing import Callable

from .snapshot import (LegacySnapshot, SnapshotItem, SnapshotRejection, SnapshotRepository,
                       SnapshotStatus, collection_digest, normalize_asset, normalize_link_job)


class SnapshotCaptureService:
    """在旧库只读事务中捕获高水位以内的数据。"""

    def __init__(self, legacy_connection_factory: Callable, repository: SnapshotRepository):
        self._legacy_connection_factory = legacy_connection_factory
        self._repository = repository

    def capture(self, source_schema: str = "downloadbot") -> LegacySnapshot:
        """捕获资产和链接历史，并在新 schema 中原子封存。"""
        if not source_schema or len(source_schema) > 128:
            raise ValueError("source schema is invalid")
        snapshot = LegacySnapshot(source_schema=source_schema)
        self._repository.begin(snapshot)
        connection = self._legacy_connection_factory()
        try:
            with connection.cursor() as cursor:
                # 一致性视图必须在任何普通查询之前创建。
                cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
                cursor.execute("SELECT COALESCE(MAX(id),0) AS value FROM assets")
                asset_high_water = int(cursor.fetchone()["value"])
                cursor.execute("SELECT COALESCE(MAX(id),0) AS value FROM link_jobs")
                link_high_water = int(cursor.fetchone()["value"])
                cursor.execute("SELECT * FROM assets WHERE id <= %s ORDER BY id", (asset_high_water,))
                assets = list(cursor.fetchall())
                cursor.execute("SELECT * FROM link_jobs WHERE id <= %s ORDER BY id", (link_high_water,))
                links = list(cursor.fetchall())
            connection.commit()
        finally:
            connection.close()

        normalized = [normalize_asset(row) for row in assets]
        normalized.extend(normalize_link_job(row) for row in links)
        items = [value for value in normalized if isinstance(value, SnapshotItem)]
        rejections = [value for value in normalized if isinstance(value, SnapshotRejection)]
        sealed = replace(snapshot, status=SnapshotStatus.SEALED,
                         high_water={"assets": asset_high_water, "linkJobs": link_high_water},
                         item_count=len(items), rejection_count=len(rejections),
                         collection_sha256=collection_digest(items), sealed_at=datetime.now(UTC))
        return self._repository.seal(sealed, items, rejections)
