"""历史消息快照领域模型和边界校验。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import re
from typing import Any

SOURCE_SYSTEM = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
CHANNEL_TYPES = {"EMAIL", "QQ", "TELEGRAM", "ONEBOT"}
PART_TYPES = {"TEXT", "ATTACHMENT"}
ATTACHMENT_TYPES = {"IMAGE", "VIDEO", "RECORD", "FILE"}
SHA256 = re.compile(r"^[a-f0-9]{64}$")


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

    def migration_digest(self) -> str:
        """计算与 Messaging 历史迁移记录一致的字段摘要。"""
        digest = hashlib.sha256()
        update_digest(digest, self.source_system, self.legacy_message_id, str(self.owner_id),
                      self.channel_type, self.conversation_key, self.sender, self.subject,
                      self.body, canonical_instant(self.received_at))
        for part in self.parts:
            size = part.get("declaredSize")
            update_digest(digest, part.get("type"), part.get("text"),
                          part.get("attachmentType"), part.get("providerFileId"),
                          part.get("sourceUrl"), part.get("fileName"), part.get("mimeType"),
                          None if size is None else str(size))
        return digest.hexdigest()


def update_digest(digest, *values: object) -> None:
    """使用共享长度前缀协议更新摘要。"""
    for value in values:
        encoded = ("" if value is None else str(value)).encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)


def canonical_instant(value: str) -> str:
    """规范化为 Java Instant.toString 使用的 UTC 形式。"""
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)
    match = re.search(r":\d{2}:\d{2}(?:\.(\d{1,9}))?(?:Z|[+-]\d{2}:\d{2})$", value)
    fraction = "" if match is None or match.group(1) is None else match.group(1)
    nanoseconds = int(fraction.ljust(9, "0")) if fraction else 0
    if nanoseconds == 0:
        suffix = ""
    elif nanoseconds % 1_000_000 == 0:
        suffix = f".{nanoseconds // 1_000_000:03d}"
    elif nanoseconds % 1_000 == 0:
        suffix = f".{nanoseconds // 1_000:06d}"
    else:
        suffix = f".{nanoseconds:09d}"
    return parsed.replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%S") + suffix + "Z"


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


@dataclass(frozen=True)
class OutboundSnapshot:
    """可安全导出的标准历史发件快照。"""

    value: dict[str, Any]

    @property
    def source_system(self) -> str:
        """返回来源系统。"""
        return self.value["sourceSystem"]

    @property
    def legacy_message_id(self) -> str:
        """返回旧消息标识。"""
        return self.value["legacyMessageId"]

    @staticmethod
    def from_document(document: dict[str, Any]) -> "OutboundSnapshot":
        """严格校验历史发件文档和附件归档引用。"""
        allowed = {"sourceSystem", "legacyMessageId", "ownerId", "channelType", "status",
                   "sender", "recipients", "subject", "bodyText", "bodyHtml", "attachments",
                   "templateRef", "providerMessageId", "errorCode", "sentAt", "createdAt"}
        if not isinstance(document, dict) or set(document) - allowed:
            raise ValueError("outbound snapshot contains unsupported fields")
        result: dict[str, Any] = {
            "sourceSystem": text(document, "sourceSystem", 64),
            "legacyMessageId": text(document, "legacyMessageId", 255),
            "channelType": text(document, "channelType", 32),
            "status": text(document, "status", 32),
            "createdAt": valid_instant(document, "createdAt", required=True),
        }
        if not SOURCE_SYSTEM.fullmatch(result["sourceSystem"]):
            raise ValueError("sourceSystem is invalid")
        owner_id = document.get("ownerId")
        if isinstance(owner_id, bool) or not isinstance(owner_id, int) or owner_id < 0:
            raise ValueError("ownerId is invalid")
        result["ownerId"] = owner_id
        if result["channelType"] != "EMAIL" or result["status"] not in {"SENT", "FAILED"}:
            raise ValueError("outbound channel or status is invalid")
        recipients = document.get("recipients")
        if not isinstance(recipients, list) or not recipients or len(recipients) > 200:
            raise ValueError("recipients is invalid")
        result["recipients"] = [limited_string(item, "recipient", 1024) for item in recipients]
        for name, maximum in (("sender", 1024), ("subject", 998), ("bodyText", 10_485_760),
                              ("bodyHtml", 10_485_760), ("templateRef", 255),
                              ("providerMessageId", 512), ("errorCode", 255)):
            value = document.get(name)
            if value is not None:
                result[name] = limited_string(value, name, maximum, allow_empty=True)
        if not result.get("bodyText") and not result.get("bodyHtml"):
            raise ValueError("outbound message body is missing")
        sent_at = valid_instant(document, "sentAt", required=result["status"] == "SENT")
        if sent_at is not None:
            result["sentAt"] = sent_at
        attachments = document.get("attachments", [])
        if not isinstance(attachments, list) or len(attachments) > 100:
            raise ValueError("attachments is invalid")
        result["attachments"] = [validate_archive(item) for item in attachments]
        return OutboundSnapshot(result)

    def document(self) -> dict[str, Any]:
        """返回 Messaging 历史发件迁移文档。"""
        return json.loads(json.dumps(self.value, ensure_ascii=False))

    def digest(self) -> str:
        """计算规范载荷摘要。"""
        payload = json.dumps(self.value, sort_keys=True, separators=(",", ":"),
                             ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()

    def migration_digest(self) -> str:
        """返回与 Messaging Java record 序列化一致的载荷摘要。"""
        keys = ("sourceSystem", "legacyMessageId", "ownerId", "channelType", "status", "sender",
                "recipients", "subject", "bodyText", "bodyHtml", "attachments", "templateRef",
                "providerMessageId", "errorCode", "sentAt", "createdAt")
        normalized = {key: self.value.get(key) for key in keys}
        normalized["attachments"] = [
            {key: attachment.get(key) for key in ("fileName", "mimeType", "availability", "size",
                                                  "sha256", "archiveRef", "legacyContentRef")}
            for attachment in normalized["attachments"]]
        payload = json.dumps(normalized, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()


def valid_instant(document: dict[str, Any], name: str, required: bool) -> str | None:
    """校验 ISO 时间字符串。"""
    value = document.get(name)
    if value is None and not required:
        return None
    value = limited_string(value, name, 64)
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise ValueError(f"{name} is invalid") from exception
    return value


def limited_string(value: Any, name: str, maximum: int, allow_empty: bool = False) -> str:
    """校验长度受限的字符串。"""
    if not isinstance(value, str) or (not allow_empty and not value) or len(value) > maximum:
        raise ValueError(f"{name} is invalid")
    return value


def validate_archive(value: Any) -> dict[str, Any]:
    """校验内容寻址附件或旧源缺失证据。"""
    allowed = {"fileName", "mimeType", "availability", "size", "sha256", "archiveRef",
               "legacyContentRef"}
    if not isinstance(value, dict) or set(value) != allowed:
        raise ValueError("attachment archive fields are invalid")
    size = value.get("size")
    if size is not None and (isinstance(size, bool) or not isinstance(size, int) or size < 0):
        raise ValueError("attachment size is invalid")
    availability = value.get("availability")
    sha256 = value.get("sha256")
    archive_ref = value.get("archiveRef")
    legacy_ref = value.get("legacyContentRef")
    if availability == "ARCHIVED":
        sha256 = limited_string(sha256, "attachment sha256", 64)
        archive_ref = limited_string(archive_ref, "attachment archiveRef", 2048)
        if size is None or not SHA256.fullmatch(sha256) \
                or archive_ref != f"msgservice-archive://sha256/{sha256}" or legacy_ref is not None:
            raise ValueError("attachment archive identity is invalid")
    elif availability == "MISSING":
        legacy_ref = limited_string(legacy_ref, "attachment legacyContentRef", 4096)
        if sha256 is not None or archive_ref is not None:
            raise ValueError("missing attachment evidence is invalid")
    else:
        raise ValueError("attachment availability is invalid")
    mime_type = value.get("mimeType")
    if mime_type is not None:
        mime_type = limited_string(mime_type, "attachment mimeType", 255, allow_empty=True)
    return {"fileName": limited_string(value.get("fileName"), "attachment fileName", 1024),
            "mimeType": mime_type, "availability": availability, "size": size, "sha256": sha256,
            "archiveRef": archive_ref, "legacyContentRef": legacy_ref}
