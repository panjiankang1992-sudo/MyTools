"""历史消息快照领域模型和边界校验。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import re
from typing import Any

SOURCE_SYSTEM = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
CHANNEL_TYPES = {"EMAIL", "QQ", "TELEGRAM", "ONEBOT"}
PART_TYPES = {"TEXT", "ATTACHMENT"}
ATTACHMENT_TYPES = {"IMAGE", "VIDEO", "RECORD", "FILE"}


@dataclass(frozen=True)
class Snapshot:
    """可安全导出的标准历史消息快照。"""

    source_system: str
    legacy_message_id: str
    owner_id: int
    channel_type: str
    conversation_key: str
    sender: str
    subject: str | None
    body: str
    received_at: str
    parts: tuple[dict[str, Any], ...]

    @staticmethod
    def from_document(document: dict[str, Any]) -> "Snapshot":
        """校验外部快照文档并建立不可变模型。"""
        allowed = {"sourceSystem", "legacyMessageId", "ownerId", "channelType",
                   "conversationKey", "sender", "subject", "body", "receivedAt", "parts"}
        if not isinstance(document, dict) or set(document) - allowed:
            raise ValueError("snapshot contains unsupported fields")
        source_system = text(document, "sourceSystem", 64)
        if not SOURCE_SYSTEM.fullmatch(source_system):
            raise ValueError("sourceSystem is invalid")
        legacy_message_id = text(document, "legacyMessageId", 255)
        owner_id = document.get("ownerId")
        if isinstance(owner_id, bool) or not isinstance(owner_id, int) or owner_id < 0:
            raise ValueError("ownerId is invalid")
        channel_type = text(document, "channelType", 32)
        if channel_type not in CHANNEL_TYPES:
            raise ValueError("channelType is invalid")
        subject_value = document.get("subject")
        if subject_value is not None and (not isinstance(subject_value, str) or len(subject_value) > 998):
            raise ValueError("subject is invalid")
        received_at = text(document, "receivedAt", 64)
        try:
            datetime.fromisoformat(received_at.replace("Z", "+00:00"))
        except ValueError as exception:
            raise ValueError("receivedAt is invalid") from exception
        parts_value = document.get("parts", [])
        if not isinstance(parts_value, list) or len(parts_value) > 500:
            raise ValueError("parts is invalid")
        parts = tuple(validate_part(part) for part in parts_value)
        return Snapshot(source_system, legacy_message_id, owner_id, channel_type,
                        text(document, "conversationKey", 512), text(document, "sender", 1024),
                        subject_value, text(document, "body", 10_485_760), received_at, parts)

    def document(self) -> dict[str, Any]:
        """返回 Messaging 历史迁移接口接受的文档。"""
        return {"sourceSystem": self.source_system, "legacyMessageId": self.legacy_message_id,
                "ownerId": self.owner_id, "channelType": self.channel_type,
                "conversationKey": self.conversation_key, "sender": self.sender,
                "subject": self.subject, "body": self.body, "receivedAt": self.received_at,
                "parts": [dict(part) for part in self.parts]}

    def digest(self) -> str:
        """计算稳定规范 JSON 摘要。"""
        payload = json.dumps(self.document(), sort_keys=True, separators=(",", ":"),
                             ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()


def text(document: dict[str, Any], name: str, maximum: int) -> str:
    """读取必填且长度受限的字符串。"""
    value = document.get(name)
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ValueError(f"{name} is invalid")
    return value


def validate_part(value: Any) -> dict[str, Any]:
    """校验消息分段并拒绝未知或敏感字段。"""
    if not isinstance(value, dict):
        raise ValueError("message part is invalid")
    allowed = {"type", "text", "attachmentType", "providerFileId", "sourceUrl",
               "fileName", "mimeType", "declaredSize"}
    if set(value) - allowed:
        raise ValueError("message part contains unsupported fields")
    part_type = value.get("type")
    if part_type not in PART_TYPES:
        raise ValueError("message part type is invalid")
    limits = {"text": 10_485_760, "providerFileId": 512, "sourceUrl": 4096,
              "fileName": 1024, "mimeType": 255}
    result: dict[str, Any] = {"type": part_type}
    for name, maximum in limits.items():
        item = value.get(name)
        if item is not None:
            if not isinstance(item, str) or len(item) > maximum:
                raise ValueError(f"message part {name} is invalid")
            result[name] = item
    attachment_type = value.get("attachmentType")
    if attachment_type is not None:
        if attachment_type not in ATTACHMENT_TYPES:
            raise ValueError("message part attachmentType is invalid")
        result["attachmentType"] = attachment_type
    declared_size = value.get("declaredSize")
    if declared_size is not None:
        if isinstance(declared_size, bool) or not isinstance(declared_size, int) or declared_size <= 0:
            raise ValueError("message part declaredSize is invalid")
        result["declaredSize"] = declared_size
    return result
