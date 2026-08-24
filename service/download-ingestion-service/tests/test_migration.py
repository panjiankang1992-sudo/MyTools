import hashlib
import json

from mytools_download_ingestion.migration import (
    InMemoryLegacyHistoryRepository,
    LegacyHistoryMigrationService,
)


def item(payload=None):
    payload = payload or link_payload()
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    digest = hashlib.sha256(encoded).hexdigest()
    return {"snapshotItemId": "item-8", "itemType": "LINK_JOB", "legacyId": "8",
            "sourceKey": "link:8", "payload": payload, "payloadSha256": digest}


def link_payload(status="COMPLETED"):
    return {"legacyJobId": "8", "uriSha256": "a" * 64, "requestKind": "HTTP",
            "strategy": "DIRECT", "sourceType": "MESSAGE", "sourceKey": "message-8",
            "status": status, "expectedFiles": 1, "createdAt": None, "completedAt": None}


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
                             [item(link_payload("FAILED"))])
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


def test_history_import_accepts_sanitized_event_asset_relation():
    repository = InMemoryLegacyHistoryRepository()
    payload = {"legacyAssetSourceId": "7", "legacyAssetId": "3",
               "eventKeySha256": "b" * 64, "sourceSystem": "DOWNLOADBOT_ONEBOT",
               "sourceIndex": 0, "contentSha256": "c" * 64, "receivedAt": None}
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True,
                                       separators=(",", ":")).encode()).hexdigest()
    relation = {"itemType": "EVENT_ASSET", "legacyId": "7",
                "sourceKey": f"event-asset:{'b' * 64}:0", "payload": payload,
                "payloadSha256": digest}

    result = LegacyHistoryMigrationService(repository).migrate(
        "download-v2", "DownloadBot", False, [relation])

    assert result["accepted"] == 1
    assert ("DownloadBot", "EVENT_ASSET", "7") in repository.records


def test_history_import_rejects_unknown_or_private_event_fields():
    payload = {"legacyAssetSourceId": "7", "legacyAssetId": "3",
               "eventKeySha256": "b" * 64, "sourceSystem": "DOWNLOADBOT_QQ",
               "sourceIndex": 0, "contentSha256": "c" * 64, "receivedAt": None,
               "rawPayload": {"sender": "private"}}
    invalid = {"itemType": "EVENT_ASSET", "legacyId": "7",
               "sourceKey": "event-asset:invalid:0", "payload": payload,
               "payloadSha256": "d" * 64}

    result = LegacyHistoryMigrationService(InMemoryLegacyHistoryRepository()).migrate(
        "download-v2", "DownloadBot", True, [invalid])

    assert result["rejected"] == 1


def test_history_import_preserves_ingress_event_and_message_records():
    repository = InMemoryLegacyHistoryRepository()
    service = LegacyHistoryMigrationService(repository)
    event_payload = {"legacyEventRowId": "4", "platform": "telegram",
                     "botAccountId": "bot", "eventId": "event",
                     "rawPayload": {"text": "keep me"}, "receivedAt": None,
                     "status": "COMPLETED", "processingStage": "DONE", "error": "",
                     "createdAt": None, "updatedAt": None}
    message_payload = {"legacyMessageId": "5", "legacyEventRowId": "4",
                       "platform": "telegram", "botAccountId": "bot", "eventId": "event",
                       "platformMessageId": "message", "conversationId": "conversation",
                       "senderId": "sender", "receivedAt": None}

    result = service.migrate("download-v3", "DownloadBot", False, [
        typed_item("INGRESS_EVENT", "4", "event:key", event_payload),
        typed_item("MESSAGE", "5", "message:key", message_payload)])

    assert result["accepted"] == 2
    assert repository.records[("DownloadBot", "INGRESS_EVENT", "4")]["payload"][
        "rawPayload"] == {"text": "keep me"}
    assert ("DownloadBot", "MESSAGE", "5") in repository.records


def typed_item(item_type, legacy_id, source_key, payload):
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return {"itemType": item_type, "legacyId": legacy_id, "sourceKey": source_key,
            "payload": payload, "payloadSha256": hashlib.sha256(encoded).hexdigest()}
