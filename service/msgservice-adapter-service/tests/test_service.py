"""快照服务行为和 Messaging 迁移契约测试。"""

import importlib.util
from pathlib import Path

import pytest

from mytools_msgservice_adapter.repository import InMemorySnapshotRepository
from mytools_msgservice_adapter.models import canonical_instant
from mytools_msgservice_adapter.service import SnapshotService


def message(identifier: str = "mail-1", body: str = "hello") -> dict:
    """创建最小标准历史消息。"""
    return {"sourceSystem": "MsgService", "legacyMessageId": identifier, "ownerId": 7,
            "channelType": "EMAIL", "conversationKey": "mailbox:inbox",
            "sender": "sender@example.com", "subject": "subject", "body": body,
            "receivedAt": "2026-08-23T10:00:00Z",
            "parts": [{"type": "TEXT", "text": body}]}


def test_capabilities_are_default_off() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), False, False)
    with pytest.raises(PermissionError, match="import is disabled"):
        service.import_snapshots([message()])
    with pytest.raises(PermissionError, match="export is disabled"):
        service.export_page(None, 200)


def test_import_is_idempotent_and_rejects_payload_conflict() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), True, True)
    first = service.import_snapshots([message()])
    replay = service.import_snapshots([message()])
    conflict = service.import_snapshots([message(body="changed")])
    assert (first.accepted, first.skipped, first.rejected) == (1, 0, 0)
    assert (replay.accepted, replay.skipped, replay.rejected) == (0, 1, 0)
    assert (conflict.accepted, conflict.skipped, conflict.rejected) == (0, 0, 1)
    assert first.digest_sha256 == replay.digest_sha256


def test_export_uses_bounded_stable_cursor_and_exact_migration_shape() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), True, True)
    service.import_snapshots([message("mail-1"), message("mail-2"), message("mail-3")])
    first = service.export_page(None, 2)
    second = service.export_page(first["nextAfterId"], 2, first["snapshotHighWater"])
    assert [item["legacyMessageId"] for item in first["items"]] == ["mail-1", "mail-2"]
    assert [item["legacyMessageId"] for item in second["items"]] == ["mail-3"]
    assert second["nextAfterId"] is None
    assert first["itemCount"] == second["itemCount"] == 3
    assert first["collectionSha256"] == second["collectionSha256"]
    assert set(first["items"][0]) == {"sourceSystem", "legacyMessageId", "ownerId",
                                             "channelType", "conversationKey", "sender", "subject",
                                             "body", "receivedAt", "parts"}


def test_export_high_water_excludes_concurrent_appends() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), True, True)
    service.import_snapshots([message("mail-1"), message("mail-2")])
    first = service.export_page(None, 1)
    service.import_snapshots([message("mail-3")])

    second = service.export_page(first["nextAfterId"], 1, first["snapshotHighWater"])

    assert [item["legacyMessageId"] for item in second["items"]] == ["mail-2"]
    assert second["itemCount"] == 2
    assert second["collectionSha256"] == first["collectionSha256"]


def test_collection_digest_matches_messaging_reconciliation_protocol() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), True, True)
    fixture = message("fixture-1", "historical body")
    fixture["sourceSystem"] = "MSGSERVICE"
    fixture["receivedAt"] = "2026-01-02T03:04:05Z"
    service.import_snapshots([fixture])

    evidence = service.export_page(None, 200)

    assert evidence["collectionSha256"] == \
        "40cd2098a515a6ba61ae58bde61eb568c4d028b9ef8a172251ca131fadd0ee90"


def test_collection_digest_uses_identity_order_instead_of_insert_order() -> None:
    first = SnapshotService(InMemorySnapshotRepository(), True, True)
    second = SnapshotService(InMemorySnapshotRepository(), True, True)
    values = [message("message-b", "body-b"), message("message-a", "body-a")]
    first.import_snapshots(values)
    second.import_snapshots(list(reversed(values)))

    assert first.export_page(None, 200)["collectionSha256"] == \
        second.export_page(None, 200)["collectionSha256"]


def test_instant_normalization_matches_java_fraction_groups() -> None:
    assert canonical_instant("2026-01-02T11:04:05.1234+08:00") == \
        "2026-01-02T03:04:05.123400Z"
    assert canonical_instant("2026-01-02T03:04:05.123456789Z") == \
        "2026-01-02T03:04:05.123456789Z"


def test_unknown_fields_and_unbounded_pages_are_rejected() -> None:
    repository = InMemorySnapshotRepository()
    service = SnapshotService(repository, True, True)
    unsafe = message()
    unsafe["password"] = "secret"
    with pytest.raises(ValueError, match="unsupported fields"):
        service.import_snapshots([message("valid-before-error"), unsafe])
    assert repository.page(0, repository.high_water(), 200) == []
    with pytest.raises(ValueError, match="limit is invalid"):
        service.export_page(None, 201)


def test_message_migration_script_consumes_adapter_pages() -> None:
    repository = InMemorySnapshotRepository()
    service = SnapshotService(repository, True, True)
    service.import_snapshots([message("mail-1"), message("mail-2")])
    script = (Path(__file__).parents[2] / "messaging-service" / "packages" /
              "message_migrate_history" / "1.0.0" / "scripts" / "main.py")
    spec = importlib.util.spec_from_file_location("message_migrate_history_contract", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    class ContractClient:
        def page(self, after_id):
            return service.export_page(after_id, 200)

        def import_batch(self, _migration_key, dry_run, items):
            assert dry_run is True
            assert all(set(item) == {"sourceSystem", "legacyMessageId", "ownerId", "channelType",
                                     "conversationKey", "sender", "subject", "body", "receivedAt",
                                     "parts"} for item in items)
            return {"dryRun": True, "accepted": len(items), "skipped": 0, "rejected": 0,
                    "digestSha256": "0" * 64}

    result = module.execute(ContractClient(), "msgservice-20260823", True)
    assert result["exported"] == 2
    assert result["accepted"] == 2
    assert result["rejected"] == 0
