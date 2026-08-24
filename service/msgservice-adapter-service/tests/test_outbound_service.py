"""历史发件快照服务测试。"""

from copy import deepcopy

from mytools_msgservice_adapter.outbound_repository import InMemoryOutboundSnapshotRepository
from mytools_msgservice_adapter.outbound_service import OutboundSnapshotService
from mytools_msgservice_adapter.models import OutboundSnapshot


def message(identifier: str = "mail-1") -> dict:
    """建立最小有效发件快照。"""
    digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    return {"sourceSystem": "MSGSERVICE", "legacyMessageId": identifier, "ownerId": 0,
            "channelType": "EMAIL", "status": "SENT", "sender": "sender@example.com",
            "recipients": ["recipient@example.com"], "subject": "subject", "bodyText": "body",
            "attachments": [{"fileName": "test.txt", "mimeType": "text/plain", "size": 4,
                             "sha256": digest, "archiveRef": f"msgservice-archive://sha256/{digest}"}],
            "providerMessageId": "provider-1", "sentAt": "2026-08-22T01:02:03Z",
            "createdAt": "2026-08-22T01:02:00Z"}


def test_import_replay_conflict_and_stable_export() -> None:
    """验证幂等冲突和冻结分页。"""
    service = OutboundSnapshotService(InMemoryOutboundSnapshotRepository(), True, True)
    first = service.import_snapshots([message("mail-1"), message("mail-2")])
    replay = service.import_snapshots([message("mail-1")])
    changed = deepcopy(message("mail-1"))
    changed["bodyText"] = "changed"
    conflict = service.import_snapshots([changed])
    page = service.export_page(None, 1)
    service.import_snapshots([message("mail-3")])
    last = service.export_page(page["nextAfterId"], 1, page["snapshotHighWater"])

    assert first.accepted == 2
    assert replay.skipped == 1
    assert conflict.rejected == 1
    assert page["itemCount"] == 2
    assert [item["legacyMessageId"] for item in last["items"]] == ["mail-2"]


def test_rejects_embedded_attachment_content() -> None:
    """验证适配器不会接收入库字节。"""
    service = OutboundSnapshotService(InMemoryOutboundSnapshotRepository(), True, True)
    unsafe = message()
    unsafe["attachments"][0]["content"] = {"type": "Buffer", "data": [1, 2]}
    try:
        service.import_snapshots([unsafe])
    except ValueError as exception:
        assert "attachment archive fields" in str(exception)
    else:
        raise AssertionError("embedded attachment content was accepted")


def test_migration_digest_matches_messaging_record_serialization() -> None:
    """锁定 Python 与 Java 迁移载荷摘要协议。"""
    assert OutboundSnapshot.from_document(message()).migration_digest() == \
        "1c8bd136ce01635b093e9bd7d3f29d054e845335050b681f100e4e222434a4a3"
