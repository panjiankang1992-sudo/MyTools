"""历史发件快照装载与导出服务。"""

from __future__ import annotations

import hashlib
from typing import Any

from .models import OutboundSnapshot
from .outbound_repository import OutboundSnapshotRepository
from .service import ImportResult, decode_cursor, encode_cursor


class OutboundSnapshotService:
    """实施默认关闭的发件快照装载和导出。"""

    def __init__(self, repository: OutboundSnapshotRepository,
                 import_enabled: bool, export_enabled: bool):
        self._repository = repository
        self._import_enabled = import_enabled
        self._export_enabled = export_enabled

    def import_snapshots(self, documents: list[dict[str, Any]]) -> ImportResult:
        """幂等装载有界历史发件批次。"""
        if not self._import_enabled:
            raise PermissionError("outbound snapshot import is disabled")
        if not documents or len(documents) > 200:
            raise ValueError("outbound snapshot batch size is invalid")
        snapshots = [(snapshot, snapshot.digest()) for snapshot in
                     (OutboundSnapshot.from_document(document) for document in documents)]
        accepted = skipped = rejected = 0
        digest = hashlib.sha256()
        for snapshot, payload_digest in snapshots:
            digest.update(bytes.fromhex(payload_digest))
            existing = self._repository.find(snapshot.source_system, snapshot.legacy_message_id)
            if existing is not None:
                if existing.payload_sha256 == payload_digest:
                    skipped += 1
                else:
                    rejected += 1
                continue
            stored = self._repository.insert(snapshot, payload_digest)
            if stored.payload_sha256 == payload_digest:
                accepted += 1
            else:
                rejected += 1
        return ImportResult(accepted, skipped, rejected, digest.hexdigest())

    def export_page(self, after_id: str | None, limit: int,
                    snapshot_high_water: str | None = None) -> dict[str, Any]:
        """按稳定游标导出 Messaging 发件归档契约。"""
        if not self._export_enabled:
            raise PermissionError("outbound snapshot export is disabled")
        if limit < 1 or limit > 200:
            raise ValueError("limit is invalid")
        after_sequence = decode_cursor(after_id)
        high_water = self._repository.high_water() if snapshot_high_water is None \
            else decode_cursor(snapshot_high_water)
        if after_sequence > high_water:
            raise ValueError("afterId exceeds snapshot high water")
        item_count, collection_digest = self._repository.evidence(high_water)
        values = self._repository.page(after_sequence, high_water, limit + 1)
        page = values[:limit]
        next_after_id = encode_cursor(page[-1].sequence_id) if len(values) > limit and page else None
        return {"items": [item.snapshot.document() for item in page],
                "nextAfterId": next_after_id, "snapshotHighWater": encode_cursor(high_water),
                "itemCount": item_count, "collectionSha256": collection_digest}
