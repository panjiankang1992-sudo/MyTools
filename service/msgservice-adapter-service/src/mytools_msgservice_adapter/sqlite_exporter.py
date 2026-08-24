"""从 MsgService SQLite 一致备份导出历史发件和附件归档。"""

from __future__ import annotations

import argparse
import base64
from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from typing import Any

from .models import OutboundSnapshot


def create_consistent_backup(source: Path, destination: Path) -> None:
    """使用 SQLite backup API 捕获包含 WAL 的一致快照。"""
    source_uri = f"file:{source.resolve().as_posix()}?mode=ro"
    with sqlite3.connect(source_uri, uri=True) as source_connection:
        with sqlite3.connect(destination) as destination_connection:
            source_connection.backup(destination_connection)
            integrity = destination_connection.execute("PRAGMA integrity_check").fetchone()
            if integrity is None or integrity[0] != "ok":
                raise ValueError("SQLite backup integrity check failed")


def export_outbound(source: Path, output: Path, attachment_root: Path | None,
                    owner_id: int) -> dict[str, Any]:
    """创建一致备份、内容寻址附件和标准发件批次。"""
    if output.exists():
        raise FileExistsError("output directory already exists")
    output.mkdir(parents=True)
    archive = output / "attachments" / "sha256"
    archive.mkdir(parents=True)
    backup = output / "msgservice-consistent.db"
    create_consistent_backup(source, backup)
    items: list[dict[str, Any]] = []
    attachment_manifest: list[dict[str, Any]] = []
    templates: list[dict[str, Any]] = []
    recipients: list[dict[str, Any]] = []
    with sqlite3.connect(f"file:{backup.as_posix()}?mode=ro", uri=True) as connection:
        connection.row_factory = sqlite3.Row
        rows = connection.execute("SELECT * FROM messages WHERE direction='outbound' ORDER BY id").fetchall()
        for row in rows:
            item, attachments = map_message(row, archive, attachment_root, owner_id)
            # 在写文件前用适配器边界执行同一套严格校验。
            item = OutboundSnapshot.from_document(item).document()
            items.append(item)
            attachment_manifest.extend(attachments)
        templates = [map_template(row, owner_id) for row in
                     connection.execute("SELECT * FROM templates ORDER BY id").fetchall()]
        recipients = [map_recipient(row, owner_id) for row in
                      connection.execute("SELECT * FROM known_recipients ORDER BY id").fetchall()]
    document = {"items": items}
    write_json(output / "outbound-batch.json", document)
    write_json(output / "attachment-manifest.json", {"attachments": attachment_manifest})
    reference_data = {"migrationKey": "msgservice-reference-20260824", "dryRun": True,
                      "templates": templates, "recipients": recipients}
    write_json(output / "reference-data-batch.json", reference_data)
    evidence = {"sourceMessageCount": len(items),
                "attachmentCount": len(attachment_manifest),
                "sentCount": sum(item["status"] == "SENT" for item in items),
                "failedCount": sum(item["status"] == "FAILED" for item in items),
                "missingAttachmentCount": sum(item["availability"] == "MISSING"
                                              for item in attachment_manifest),
                "templateCount": len(templates),
                "knownRecipientCount": len(recipients),
                "batchSha256": sha256_json(document),
                "referenceDataSha256": sha256_json(reference_data),
                "createdAt": datetime.now(UTC).isoformat().replace("+00:00", "Z")}
    write_json(output / "reconciliation.json", evidence)
    return evidence


def map_template(row: sqlite3.Row, owner_id: int) -> dict[str, Any]:
    """映射旧模板并保留变量定义。"""
    variables = json.loads(row["variables"]) if row["variables"] else None
    item = {"sourceSystem": "MSGSERVICE", "legacyTemplateId": str(row["id"]),
            "ownerId": owner_id, "channelType": str(row["channel"]).upper(),
            "name": row["name"], "description": row["description"], "subject": row["subject"],
            "bodyText": row["body_text"], "bodyHtml": row["body_html"], "variables": variables,
            "createdAt": row["created_at"], "updatedAt": row["updated_at"]}
    return {key: value for key, value in item.items() if value is not None}


def map_recipient(row: sqlite3.Row, owner_id: int) -> dict[str, Any]:
    """映射旧已知收件人。"""
    item = {"sourceSystem": "MSGSERVICE", "legacyRecipientId": str(row["id"]),
            "ownerId": owner_id, "channelType": str(row["channel"]).upper(),
            "address": row["address"], "name": row["name"], "createdAt": row["created_at"]}
    return {key: value for key, value in item.items() if value is not None}


def map_message(row: sqlite3.Row, archive: Path, attachment_root: Path | None,
                owner_id: int) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """映射一条真实 MsgService 发件记录。"""
    addresses = parse_list(row["addresses"], "addresses")
    recipients = [address["address"] for address in addresses
                  if isinstance(address, dict) and address.get("role") in {"to", "cc", "bcc"}
                  and isinstance(address.get("address"), str) and address["address"]]
    sender = next((address.get("address") for address in addresses
                   if isinstance(address, dict) and address.get("role") == "from"), None)
    attachments = parse_list(row["attachments"], "attachments")
    archives = [archive_attachment(item, archive, attachment_root, str(row["id"]), index)
                for index, item in enumerate(attachments)]
    status = str(row["status"]).upper()
    if status not in {"SENT", "FAILED"}:
        raise ValueError(f"unsupported outbound status for message {row['id']}")
    meta = parse_object(row["meta"])
    item = {"sourceSystem": "MSGSERVICE", "legacyMessageId": str(row["id"]),
            "ownerId": owner_id, "channelType": str(row["channel"]).upper(), "status": status,
            "recipients": recipients, "subject": row["subject"], "bodyText": row["body_text"],
            "bodyHtml": row["body_html"], "attachments": [value[0] for value in archives],
            "templateRef": row["template_id"], "providerMessageId": row["external_id"],
            "errorCode": meta.get("error") if isinstance(meta.get("error"), str) else None,
            "sentAt": row["at"], "createdAt": row["created_at"]}
    if sender:
        item["sender"] = sender
    return {key: value for key, value in item.items() if value is not None}, [value[1] for value in archives]


def archive_attachment(value: Any, archive: Path, attachment_root: Path | None,
                       message_id: str, index: int) -> tuple[dict[str, Any], dict[str, Any]]:
    """提取一个附件并写入内容寻址归档。"""
    if not isinstance(value, dict) or not isinstance(value.get("filename"), str):
        raise ValueError(f"attachment {index} of message {message_id} is invalid")
    content = value.get("content")
    try:
        data = attachment_bytes(content, attachment_root)
    except FileNotFoundError:
        if not isinstance(content, str):
            raise
        item = {"fileName": value["filename"], "mimeType": value.get("contentType"),
                "availability": "MISSING", "size": value.get("size"), "sha256": None,
                "archiveRef": None, "legacyContentRef": content}
        manifest = {"messageId": message_id, "attachmentIndex": index, **item,
                    "relativePath": None}
        return item, manifest
    declared_size = value.get("size")
    if declared_size is not None and declared_size != len(data):
        raise ValueError(f"attachment {index} of message {message_id} has mismatched size")
    digest = hashlib.sha256(data).hexdigest()
    destination = archive / digest
    if destination.exists():
        if destination.read_bytes() != data:
            raise ValueError("attachment digest collision")
    else:
        destination.write_bytes(data)
    reference = f"msgservice-archive://sha256/{digest}"
    item = {"fileName": value["filename"], "mimeType": value.get("contentType"),
            "availability": "ARCHIVED", "size": len(data), "sha256": digest,
            "archiveRef": reference, "legacyContentRef": None}
    manifest = {"messageId": message_id, "attachmentIndex": index, **item,
                "relativePath": f"attachments/sha256/{digest}"}
    return item, manifest


def attachment_bytes(content: Any, attachment_root: Path | None) -> bytes:
    """按旧 SMTP 规则解码 Buffer、data URI、裸 Base64 或文件。"""
    if isinstance(content, dict) and content.get("type") == "Buffer" and isinstance(content.get("data"), list):
        try:
            return bytes(content["data"])
        except ValueError as exception:
            raise ValueError("attachment Buffer contains invalid bytes") from exception
    if isinstance(content, str) and content.startswith("data:") and ";base64," in content:
        try:
            return base64.b64decode(content.split(",", 1)[1], validate=True)
        except ValueError as exception:
            raise ValueError("attachment data URI is invalid") from exception
    if isinstance(content, str) and len(content) >= 4 and len(content) % 4 == 0 \
            and re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", content):
        try:
            return base64.b64decode(content, validate=True)
        except ValueError as exception:
            raise ValueError("attachment bare base64 is invalid") from exception
    if isinstance(content, str) and attachment_root is not None:
        candidate = Path(content)
        candidate = candidate if candidate.is_absolute() else attachment_root / candidate
        resolved = candidate.resolve()
        root = attachment_root.resolve()
        if not resolved.exists():
            raise FileNotFoundError(resolved)
        if resolved != root and root not in resolved.parents:
            raise ValueError("attachment path escapes configured root")
        return resolved.read_bytes()
    raise ValueError("attachment content cannot be archived without data loss")


def parse_list(value: str, name: str) -> list[Any]:
    """解析必需的 JSON 数组。"""
    parsed = json.loads(value or "[]")
    if not isinstance(parsed, list):
        raise ValueError(f"{name} is not an array")
    return parsed


def parse_object(value: str | None) -> dict[str, Any]:
    """解析可选 JSON 对象。"""
    parsed = json.loads(value) if value else {}
    return parsed if isinstance(parsed, dict) else {}


def write_json(path: Path, value: Any) -> None:
    """以稳定格式写入迁移产物。"""
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                               separators=(",", ":")), encoding="utf-8")


def sha256_json(value: Any) -> str:
    """计算稳定 JSON 摘要。"""
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def main() -> None:
    """解析命令行并运行只读导出。"""
    parser = argparse.ArgumentParser(description="Export MsgService outbound history safely")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--attachment-root", type=Path)
    parser.add_argument("--owner-id", type=int, default=0)
    arguments = parser.parse_args()
    if arguments.owner_id < 0:
        parser.error("--owner-id must be non-negative")
    result = export_outbound(arguments.source, arguments.output,
                             arguments.attachment_root, arguments.owner_id)
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
