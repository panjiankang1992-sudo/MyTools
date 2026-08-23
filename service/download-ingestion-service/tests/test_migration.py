import hashlib
import json

from mytools_download_ingestion.migration import (
    InMemoryLegacyHistoryRepository,
    LegacyHistoryMigrationService,
)


def item(payload=None):
    payload = payload or {"legacyJobId": "8", "status": "COMPLETED"}
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"snapshotItemId": "item-8", "itemType": "LINK_JOB", "legacyId": "8",
            "sourceKey": "link:8", "payload": payload, "payloadSha256": digest}


def test_history_import_supports_dry_run_apply_and_idempotent_replay():
    repository = InMemoryLegacyHistoryRepository()
    service = LegacyHistoryMigrationService(repository)
    assert service.migrate("download-v1", "DownloadBot", True, [item()])["accepted"] == 1
    assert repository.records == {}
    assert service.migrate("download-v1", "DownloadBot", False, [item()])["accepted"] == 1
    replay = service.migrate("download-v1", "DownloadBot", False, [item()])
    assert replay["skipped"] == 1


def test_history_import_rejects_changed_payload_for_same_identity():
    repository = InMemoryLegacyHistoryRepository()
    service = LegacyHistoryMigrationService(repository)
    service.migrate("download-v1", "DownloadBot", False, [item()])
    result = service.migrate("download-v2", "DownloadBot", False,
                             [item({"legacyJobId": "8", "status": "FAILED"})])
    assert result["rejected"] == 1
    assert repository.rejections[-1][2] == "IDENTITY_CONFLICT"


def test_history_import_rejects_digest_mismatch_without_writing():
    repository = InMemoryLegacyHistoryRepository()
    invalid = item()
    invalid["payloadSha256"] = "0" * 64
    result = LegacyHistoryMigrationService(repository).migrate(
        "download-v1", "DownloadBot", False, [invalid])
    assert result["rejected"] == 1
    assert repository.records == {}
