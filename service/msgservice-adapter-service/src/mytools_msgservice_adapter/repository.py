"""历史消息快照仓储契约及内存实现。"""

from __future__ import annotations

from dataclasses import dataclass
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

    def page(self, after_sequence: int, limit: int) -> list[StoredSnapshot]:
        """按稳定序号读取一页。"""


class InMemorySnapshotRepository:
    """测试使用的确定性快照仓储。"""

    def __init__(self) -> None:
        self._values: list[StoredSnapshot] = []

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

    def page(self, after_sequence: int, limit: int) -> list[StoredSnapshot]:
        """读取稳定有界页。"""
        return [item for item in self._values if item.sequence_id > after_sequence][:limit]
