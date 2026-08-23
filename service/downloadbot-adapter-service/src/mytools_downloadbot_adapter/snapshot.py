"""旧 DownloadBot 一致性快照模型与标准化逻辑。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
import hashlib
import json
import struct
from typing import Any, Iterable, Protocol
from uuid import UUID, uuid4


class SnapshotStatus(StrEnum):
    """定义只读快照生命周期。"""

    CAPTURING = "CAPTURING"
    SEALED = "SEALED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class SnapshotItem:
    """表示可迁移的标准化旧数据。"""

    item_type: str
    legacy_id: str
    source_key: str
    payload: dict[str, Any]

    @property
    def payload_sha256(self) -> str:
        """返回规范 JSON 的内容摘要。"""
        return hashlib.sha256(canonical_json(self.payload)).hexdigest()


@dataclass(frozen=True, slots=True)
class SnapshotRejection:
    """表示无法安全标准化的旧数据。"""

    item_type: str
    legacy_id: str
    reason_code: str
    detail: str


@dataclass(frozen=True, slots=True)
class LegacySnapshot:
    """表示已捕获或正在捕获的一致性快照。"""

    id: UUID = field(default_factory=uuid4)
    status: SnapshotStatus = SnapshotStatus.CAPTURING
    source_schema: str = "downloadbot"
    source_version: str = "mysql-v1"
    high_water: dict[str, int] = field(default_factory=dict)
    item_count: int = 0
    rejection_count: int = 0
    collection_sha256: str | None = None
    started_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    sealed_at: datetime | None = None


class SnapshotRepository(Protocol):
    """定义快照写入边界。"""

    def begin(self, snapshot: LegacySnapshot) -> None:
        """创建捕获中的快照。"""

    def seal(self, snapshot: LegacySnapshot, items: list[SnapshotItem],
             rejections: list[SnapshotRejection]) -> LegacySnapshot:
        """原子写入快照明细并封存。"""

    def get(self, snapshot_id: UUID) -> LegacySnapshot | None:
        """查询快照元数据。"""

    def items(self, snapshot_id: UUID) -> list[SnapshotItem]:
        """仅返回已封存快照的标准化条目。"""

    def fail(self, snapshot_id: UUID) -> None:
        """将未完成快照标记为失败。"""


def canonical_json(value: Any) -> bytes:
    """生成跨进程稳定的 UTF-8 JSON。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), default=str).encode("utf-8")


def collection_digest(items: Iterable[SnapshotItem]) -> str:
    """计算与读取批次无关的快照集合摘要。"""
    digest = hashlib.sha256()
    for item in sorted(items, key=lambda value: (value.item_type, value.legacy_id)):
        for value in (item.item_type, item.legacy_id, item.payload_sha256):
            encoded = value.encode("utf-8")
            digest.update(len(encoded).to_bytes(4, "big"))
            digest.update(encoded)
    return digest.hexdigest()


def content_set_digest(items: Iterable[dict[str, Any]]) -> str:
    """计算与旧新执行标识无关的内容集合摘要。"""
    digest = hashlib.sha256()
    values = sorted((str(item["fileName"]), str(item["contentSha256"]).lower(),
                     int(item["sizeBytes"])) for item in items)
    for item in values:
        for value in item:
            encoded = str(value).encode("utf-8")
            digest.update(struct.pack(">I", len(encoded)))
            digest.update(encoded)
    return digest.hexdigest()


def normalize_asset(row: dict[str, Any]) -> SnapshotItem | SnapshotRejection:
    """将旧资产映射为不暴露物理路径的可迁移记录。"""
    legacy_id = str(row.get("id", ""))
    sha256 = str(row.get("sha256", "")).lower()
    size = int(row.get("size") or 0)
    if len(sha256) != 64 or any(char not in "0123456789abcdef" for char in sha256):
        return SnapshotRejection("ASSET", legacy_id, "INVALID_SHA256", "asset checksum is invalid")
    if size < 0:
        return SnapshotRejection("ASSET", legacy_id, "INVALID_SIZE", "asset size is negative")
    return SnapshotItem("ASSET", legacy_id, f"asset:{sha256}", {
        "legacyAssetId": legacy_id,
        "contentSha256": sha256,
        "fileName": str(row.get("file_name") or ""),
        "mimeType": str(row.get("mime") or "application/octet-stream"),
        "sizeBytes": size,
        "category": str(row.get("category") or "OTHER"),
        "tagStatus": str(row.get("tag_status") or ""),
        "tags": _json_object(row.get("tags_json")),
        "createdAt": row.get("created_at"),
    })


def normalize_link_job(row: dict[str, Any]) -> SnapshotItem | SnapshotRejection:
    """将链接作业映射为不包含原始 URL 和路由凭据的历史记录。"""
    legacy_id = str(row.get("id", ""))
    uri_sha256 = str(row.get("uri_sha256") or "").lower()
    if len(uri_sha256) != 64:
        return SnapshotRejection("LINK_JOB", legacy_id, "INVALID_URI_DIGEST",
                                 "link digest is invalid")
    return SnapshotItem("LINK_JOB", legacy_id, f"link:{uri_sha256}", {
        "legacyJobId": legacy_id,
        "uriSha256": uri_sha256,
        "requestKind": str(row.get("link_kind") or "UNKNOWN"),
        "strategy": str(row.get("strategy") or "UNKNOWN"),
        "sourceType": str(row.get("source_type") or "LEGACY"),
        "sourceKey": str(row.get("source_key") or legacy_id),
        "status": str(row.get("status") or "UNKNOWN"),
        "expectedFiles": int(row.get("expected_files") or 0),
        "createdAt": row.get("created_at"),
        "completedAt": row.get("completed_at"),
    })


def normalize_link_asset(row: dict[str, Any]) -> SnapshotItem | SnapshotRejection:
    """将链接与资产关系映射为可对账的历史条目。"""
    legacy_id = str(row.get("id", ""))
    sha256 = str(row.get("sha256") or "").lower()
    if len(sha256) != 64 or any(char not in "0123456789abcdef" for char in sha256):
        return SnapshotRejection("LINK_ASSET", legacy_id, "INVALID_SHA256",
                                 "linked asset checksum is invalid")
    link_job_id = str(row.get("link_job_id") or "")
    asset_id = str(row.get("asset_id") or "")
    if not link_job_id or not asset_id:
        return SnapshotRejection("LINK_ASSET", legacy_id, "MISSING_RELATION",
                                 "link or asset identity is missing")
    return SnapshotItem("LINK_ASSET", legacy_id, f"link-asset:{link_job_id}:{asset_id}", {
        "legacyLinkJobId": link_job_id,
        "legacyAssetId": asset_id,
        "contentSha256": sha256,
        "sourceKey": str(row.get("source_key") or legacy_id),
        "createdAt": row.get("created_at"),
    })


def _json_object(value: Any) -> Any:
    """安全解析旧 JSON 列，失败时保留空对象。"""
    if isinstance(value, (dict, list)):
        return value
    try:
        parsed = json.loads(str(value or "{}"))
        return parsed if isinstance(parsed, (dict, list)) else {}
    except json.JSONDecodeError:
        return {}
