"""历史消息快照仓储契约及内存实现。"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
from typing import Protocol

from .models import Snapshot


@dataclass(frozen=True)
class StoredSnapshot:
    """带稳定分页序号的快照。"""

    sequence_id: int
    snapshot: Snapshot
    payload_sha256: str


class SnapshotRepository(Protocol):
    """快照持久化边界。"""

    def find(self, source_system: str, legacy_message_id: str) -> StoredSnapshot | None:
        """按旧身份查询快照。"""

    def insert(self, snapshot: Snapshot, payload_sha256: str) -> StoredSnapshot:
        """插入快照并返回持久化结果。"""

    def high_water(self) -> int:
        """读取当前追加日志高水位。"""

    def evidence(self, high_water: int) -> tuple[int, str]:
        """计算冻结高水位内的数量和集合摘要。"""

    def page(self, after_sequence: int, high_water: int, limit: int) -> list[StoredSnapshot]:
        """按稳定序号读取一页。"""


class InMemorySnapshotRepository:
    """测试使用的确定性快照仓储。"""

    def __init__(self) -> None:
        self._values: list[StoredSnapshot] = []
        self._evidence: dict[int, tuple[int, str]] = {}

    def find(self, source_system: str, legacy_message_id: str) -> StoredSnapshot | None:
        """按旧身份查询快照。"""
        return next((item for item in self._values if item.snapshot.source_system == source_system
                     and item.snapshot.legacy_message_id == legacy_message_id), None)

    def insert(self, snapshot: Snapshot, payload_sha256: str) -> StoredSnapshot:
        """插入快照。"""
        existing = self.find(snapshot.source_system, snapshot.legacy_message_id)
        if existing is not None:
            return existing
        stored = StoredSnapshot(len(self._values) + 1, snapshot, payload_sha256)
        self._values.append(stored)
        return stored

    def high_water(self) -> int:
        """读取当前追加日志高水位。"""
        return self._values[-1].sequence_id if self._values else 0

    def evidence(self, high_water: int) -> tuple[int, str]:
        """计算冻结高水位内的数量和集合摘要。"""
        if high_water in self._evidence:
            return self._evidence[high_water]
        values = sorted((item for item in self._values if item.sequence_id <= high_water),
                        key=lambda item: (item.snapshot.source_system,
                                          item.snapshot.legacy_message_id))
        digest = hashlib.sha256()
        for item in values:
            update_evidence_digest(digest, item)
        result = (len(values), digest.hexdigest())
        self._evidence[high_water] = result
        return result

    def page(self, after_sequence: int, high_water: int, limit: int) -> list[StoredSnapshot]:
        """读取稳定有界页。"""
        return [item for item in self._values
                if after_sequence < item.sequence_id <= high_water][:limit]


def update_evidence_digest(digest, item: StoredSnapshot) -> None:
    """按旧身份和不可变载荷摘要更新集合摘要。"""
    for value in (item.snapshot.source_system, item.snapshot.legacy_message_id,
                  item.snapshot.migration_digest()):
        encoded = value.encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
