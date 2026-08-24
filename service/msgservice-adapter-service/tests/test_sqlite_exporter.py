"""MsgService SQLite 发件导出器测试。"""

import json
from pathlib import Path
import sqlite3

from mytools_msgservice_adapter.sqlite_exporter import export_outbound


def test_exports_consistent_backup_and_content_addressed_attachment(tmp_path: Path) -> None:
    """验证 WAL 数据和 Buffer 附件均进入一致迁移产物。"""
    source = tmp_path / "msgsvc.db"
    connection = sqlite3.connect(source)
    connection.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE messages (
          id TEXT PRIMARY KEY, channel TEXT, direction TEXT, subject TEXT, body_text TEXT,
          body_html TEXT, addresses TEXT, attachments TEXT, template_id TEXT, status TEXT,
          external_id TEXT, meta TEXT, raw TEXT, at TEXT, created_at TEXT, updated_at TEXT);
        CREATE TABLE templates (
          id TEXT PRIMARY KEY, channel TEXT, name TEXT, description TEXT, subject TEXT,
          body_text TEXT, body_html TEXT, variables TEXT, created_at TEXT, updated_at TEXT);
        CREATE TABLE known_recipients (
          id TEXT PRIMARY KEY, channel TEXT, address TEXT, name TEXT, created_at TEXT);
    """)
    attachment = [{"filename": "test.txt", "contentType": "text/plain",
                   "content": {"type": "Buffer", "data": [116, 101, 115, 116]}, "size": 4}]
    addresses = [{"address": "recipient@example.com", "role": "to"}]
    connection.execute("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       ("mail-1", "email", "outbound", "subject", "body", None,
                        json.dumps(addresses), json.dumps(attachment), None, "sent", "provider-1",
                        "{}", None, "2026-08-22T01:02:03Z", "2026-08-22T01:02:00Z",
                        "2026-08-22T01:02:03Z"))
    connection.execute("INSERT INTO templates VALUES (?,?,?,?,?,?,?,?,?,?)",
                       ("template-1", "email", "verification", None, "subject", "body", None,
                        '{"code":{"type":"string"}}', "2026-08-22T01:00:00Z",
                        "2026-08-22T01:01:00Z"))
    connection.execute("INSERT INTO known_recipients VALUES (?,?,?,?,?)",
                       ("recipient-1", "email", "recipient@example.com", "Recipient",
                        "2026-08-22T01:00:00Z"))
    connection.commit()

    result = export_outbound(source, tmp_path / "export", None, 0)
    connection.close()
    batch = json.loads((tmp_path / "export/outbound-batch.json").read_text())
    references = json.loads((tmp_path / "export/reference-data-batch.json").read_text())
    archived = Path(tmp_path / "export/attachments/sha256" /
                    batch["items"][0]["attachments"][0]["sha256"])

    assert result == {**result, "sourceMessageCount": 1, "attachmentCount": 1,
                      "sentCount": 1, "failedCount": 0}
    assert archived.read_bytes() == b"test"
    assert len(references["templates"]) == 1
    assert len(references["recipients"]) == 1
    with sqlite3.connect(tmp_path / "export/msgservice-consistent.db") as backup:
        assert backup.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 1


def test_decodes_legacy_bare_base64_attachment(tmp_path: Path) -> None:
    """验证导出器复现旧 SMTP 对裸 Base64 的判定。"""
    from mytools_msgservice_adapter.sqlite_exporter import attachment_bytes

    assert attachment_bytes("dGVzdA==", tmp_path) == b"test"


def test_preserves_missing_legacy_attachment_evidence(tmp_path: Path) -> None:
    """验证源文件已缺失时保留旧引用而不伪造摘要。"""
    from mytools_msgservice_adapter.sqlite_exporter import archive_attachment

    item, manifest = archive_attachment(
        {"filename": "missing.txt", "contentType": "text/plain", "content": "data/missing.txt"},
        tmp_path / "archive", tmp_path, "mail-1", 0)

    assert item["availability"] == "MISSING"
    assert item["legacyContentRef"] == "data/missing.txt"
    assert item["sha256"] is None
    assert manifest["relativePath"] is None
