"""历史消息快照装载与导出服务。"""

from __future__ import annotations

import base64
from dataclasses import dataclass
import hashlib
from typing import Any

from .models import Snapshot
from .repository import SnapshotRepository


@dataclass(frozen=True)
class ImportResult:
    """快照装载结果。"""

    accepted: int
    skipped: int
    rejected: int
    digest_sha256: str


class SnapshotService:
    """实施默认关闭的快照装载和只读导出。"""

    def __init__(self, repository: SnapshotRepository, import_enabled: bool, export_enabled: bool):
        self._repository = repository
        self._import_enabled = import_enabled
        self._export_enabled = export_enabled

    def import_snapshots(self, documents: list[dict[str, Any]]) -> ImportResult:
        """幂等装载一个已脱敏的有界快照批次。"""
        if not self._import_enabled:
            raise PermissionError("snapshot import is disabled")
        if not documents or len(documents) > 200:
            raise ValueError("snapshot batch size is invalid")
        accepted = skipped = rejected = 0
        digest = hashlib.sha256()
        # 写入前先校验完整批次，避免格式错误造成部分持久化。
        snapshots = [(snapshot, snapshot.digest())
                     for snapshot in (Snapshot.from_document(document) for document in documents)]
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
            # 并发装载可能由唯一键折叠，必须根据最终持久化摘要重新分类。
            if stored.payload_sha256 == payload_digest:
                accepted += 1
            else:
                rejected += 1
        return ImportResult(accepted, skipped, rejected, digest.hexdigest())

    def export_page(self, after_id: str | None, limit: int,
                    snapshot_high_water: str | None = None) -> dict[str, Any]:
        """按稳定游标导出 Messaging 迁移契约。"""
        if not self._export_enabled:
            raise PermissionError("snapshot export is disabled")
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


def encode_cursor(sequence_id: int) -> str:
    """编码不透明分页游标。"""
    return base64.urlsafe_b64encode(str(sequence_id).encode()).decode().rstrip("=")


def decode_cursor(value: str | None) -> int:
    """解码并校验分页游标。"""
    if value is None:
        return 0
    try:
        padding = "=" * (-len(value) % 4)
        sequence = int(base64.urlsafe_b64decode(value + padding).decode())
    except (ValueError, UnicodeDecodeError) as exception:
        raise ValueError("afterId is invalid") from exception
    if sequence < 0:
        raise ValueError("afterId is invalid")
    return sequence
