"""DownloadBot 历史数据导入领域服务。"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Protocol
from uuid import uuid4

MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")
ITEM_TYPES = {"ASSET", "LINK_JOB", "LINK_ASSET"}


class LegacyHistoryRepository(Protocol):
    """定义历史记录幂等写入边界。"""

    def find_digest(self, source_system: str, item_type: str, legacy_id: str) -> str | None:
        """查询已导入记录摘要。"""

    def insert_history(self, migration_key: str, source_system: str, item: dict) -> None:
        """写入一条不可变历史记录。"""

    def record_rejection(self, migration_key: str, source_system: str,
                         item: dict, reason_code: str) -> None:
        """记录一条迁移拒绝。"""


class LegacyHistoryMigrationService:
    """校验并幂等导入标准化 DownloadBot 历史批次。"""

    def __init__(self, repository: LegacyHistoryRepository):
        self._repository = repository

    def migrate(self, migration_key: str, source_system: str,
                dry_run: bool, items: list[dict]) -> dict:
        """导入或预检一个最多五百条的批次。"""
        if not MIGRATION_KEY.fullmatch(migration_key):
            raise ValueError("migration key is invalid")
        if source_system != "DownloadBot":
            raise ValueError("source system is invalid")
        if not isinstance(dry_run, bool):
            raise ValueError("dry run flag is invalid")
        if not isinstance(items, list) or len(items) > 500:
            raise ValueError("migration batch is invalid")
        accepted = skipped = rejected = 0
        digest = hashlib.sha256()
        for item in items:
            reason = validate_item(item)
            if reason is not None:
                rejected += 1
                if not dry_run:
                    self._repository.record_rejection(
                        migration_key, source_system,
                        item if isinstance(item, dict) else {}, reason)
                continue
            payload_digest = str(item["payloadSha256"])
            digest.update(bytes.fromhex(payload_digest))
            existing = self._repository.find_digest(
                source_system, str(item["itemType"]), str(item["legacyId"]))
            if existing is not None:
                if existing == payload_digest:
                    skipped += 1
                else:
                    rejected += 1
                    if not dry_run:
                        self._repository.record_rejection(
                            migration_key, source_system, item, "IDENTITY_CONFLICT")
                continue
            accepted += 1
            if not dry_run:
                self._repository.insert_history(migration_key, source_system, item)
        return {"dryRun": dry_run, "accepted": accepted, "skipped": skipped,
                "rejected": rejected, "digestSha256": digest.hexdigest()}


def validate_item(item: object) -> str | None:
    """验证适配器条目的类型、身份和规范摘要。"""
    if not isinstance(item, dict):
        return "INVALID_ITEM"
    if str(item.get("itemType")) not in ITEM_TYPES:
        return "UNSUPPORTED_TYPE"
    if not str(item.get("legacyId") or "") or len(str(item["legacyId"])) > 255:
        return "INVALID_IDENTITY"
    if not str(item.get("sourceKey") or "") or len(str(item["sourceKey"])) > 255:
        return "INVALID_SOURCE_KEY"
    payload = item.get("payload")
    if not isinstance(payload, dict):
        return "INVALID_PAYLOAD"
    claimed = str(item.get("payloadSha256") or "")
    if not DIGEST.fullmatch(claimed):
        return "INVALID_DIGEST"
    actual = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True,
                                       separators=(",", ":"), default=str).encode()).hexdigest()
    return None if claimed == actual else "DIGEST_MISMATCH"


class InMemoryLegacyHistoryRepository:
    """为领域测试提供确定性的内存仓储。"""

    def __init__(self) -> None:
        self.records: dict[tuple[str, str, str], dict] = {}
        self.rejections: list[tuple[str, str, str]] = []

    def find_digest(self, source_system: str, item_type: str, legacy_id: str) -> str | None:
        """查询内存记录摘要。"""
        value = self.records.get((source_system, item_type, legacy_id))
        return None if value is None else str(value["payloadSha256"])

    def insert_history(self, migration_key: str, source_system: str, item: dict) -> None:
        """插入一条内存历史记录。"""
        self.records[(source_system, item["itemType"], item["legacyId"])] = {
            **item, "migrationKey": migration_key, "id": str(uuid4())}

    def record_rejection(self, migration_key: str, source_system: str,
                         item: dict, reason_code: str) -> None:
        """记录内存拒绝原因。"""
        self.rejections.append((migration_key, source_system, reason_code))
