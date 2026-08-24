"""DownloadBot 历史数据导入领域服务。"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Protocol
from uuid import uuid4

MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")
ITEM_TYPES = {"INGRESS_EVENT", "MESSAGE", "ASSET", "LINK_JOB", "LINK_ASSET", "EVENT_ASSET"}
PAYLOAD_FIELDS = {
    "INGRESS_EVENT": {"legacyEventRowId", "platform", "botAccountId", "eventId",
                      "rawPayload", "receivedAt", "status", "processingStage", "error",
                      "createdAt", "updatedAt"},
    "MESSAGE": {"legacyMessageId", "legacyEventRowId", "platform", "botAccountId",
                "eventId", "platformMessageId", "conversationId", "senderId", "receivedAt"},
    "ASSET": {"legacyAssetId", "contentSha256", "fileName", "mimeType", "sizeBytes",
              "category", "tagStatus", "tags", "createdAt"},
    "LINK_JOB": {"legacyJobId", "uriSha256", "requestKind", "strategy", "sourceType",
                 "sourceKey", "status", "expectedFiles", "createdAt", "completedAt"},
    "LINK_ASSET": {"legacyLinkJobId", "legacyAssetId", "contentSha256", "sourceKey",
                   "createdAt"},
    "EVENT_ASSET": {"legacyAssetSourceId", "legacyAssetId", "eventKeySha256",
                    "sourceSystem", "sourceIndex", "contentSha256", "receivedAt"},
}


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
    payload_reason = validate_payload(str(item["itemType"]), payload)
    if payload_reason is not None:
        return payload_reason
    claimed = str(item.get("payloadSha256") or "")
    if not DIGEST.fullmatch(claimed):
        return "INVALID_DIGEST"
    actual = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True,
                                       separators=(",", ":"), default=str).encode()).hexdigest()
    return None if claimed == actual else "DIGEST_MISMATCH"


def validate_payload(item_type: str, payload: dict) -> str | None:
    """验证每类历史载荷的固定字段和关键关系。"""
    if set(payload) != PAYLOAD_FIELDS[item_type]:
        return "INVALID_PAYLOAD_FIELDS"
    if item_type == "ASSET":
        return _validate_asset_payload(payload)
    if item_type == "LINK_JOB":
        return _validate_link_job_payload(payload)
    if item_type == "LINK_ASSET":
        return _validate_link_asset_payload(payload)
    if item_type == "INGRESS_EVENT":
        return _validate_ingress_event_payload(payload)
    if item_type == "MESSAGE":
        return _validate_message_payload(payload)
    return _validate_event_asset_payload(payload)


def _validate_event_identity(payload: dict) -> str | None:
    platform = str(payload.get("platform") or "")
    if re.fullmatch(r"[a-z0-9_-]{1,32}", platform) is None:
        return "INVALID_PLATFORM"
    if not str(payload.get("botAccountId") or "") or not str(payload.get("eventId") or ""):
        return "INVALID_EVENT_IDENTITY"
    return None


def _validate_ingress_event_payload(payload: dict) -> str | None:
    if not str(payload.get("legacyEventRowId") or ""):
        return "INVALID_RELATION"
    return _validate_event_identity(payload)


def _validate_message_payload(payload: dict) -> str | None:
    if (not str(payload.get("legacyMessageId") or "")
            or not str(payload.get("legacyEventRowId") or "")):
        return "INVALID_RELATION"
    return _validate_event_identity(payload)


def _validate_asset_payload(payload: dict) -> str | None:
    if not DIGEST.fullmatch(str(payload.get("contentSha256") or "")):
        return "INVALID_CONTENT_DIGEST"
    if str(payload.get("legacyAssetId") or "") == "":
        return "INVALID_RELATION"
    size = payload.get("sizeBytes")
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        return "INVALID_SIZE"
    return None


def _validate_link_job_payload(payload: dict) -> str | None:
    if not DIGEST.fullmatch(str(payload.get("uriSha256") or "")):
        return "INVALID_URI_DIGEST"
    expected = payload.get("expectedFiles")
    if not isinstance(expected, int) or isinstance(expected, bool) or expected < 0:
        return "INVALID_EXPECTED_FILES"
    return None


def _validate_link_asset_payload(payload: dict) -> str | None:
    if not DIGEST.fullmatch(str(payload.get("contentSha256") or "")):
        return "INVALID_CONTENT_DIGEST"
    if not str(payload.get("legacyLinkJobId") or "") or not str(payload.get("legacyAssetId") or ""):
        return "INVALID_RELATION"
    return None


def _validate_event_asset_payload(payload: dict) -> str | None:
    if (not DIGEST.fullmatch(str(payload.get("eventKeySha256") or ""))
            or not DIGEST.fullmatch(str(payload.get("contentSha256") or ""))):
        return "INVALID_CONTENT_DIGEST"
    if (not str(payload.get("legacyAssetSourceId") or "")
            or not str(payload.get("legacyAssetId") or "")):
        return "INVALID_RELATION"
    source_system = str(payload.get("sourceSystem") or "")
    if re.fullmatch(r"DOWNLOADBOT_[A-Z0-9_-]{1,32}", source_system) is None:
        return "INVALID_SOURCE_SYSTEM"
    source_index = payload.get("sourceIndex")
    if not isinstance(source_index, int) or isinstance(source_index, bool) or source_index < 0:
        return "INVALID_SOURCE_INDEX"
    return None


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
