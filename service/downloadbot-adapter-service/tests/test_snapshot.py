from datetime import UTC, datetime

from mytools_downloadbot_adapter.snapshot import (
    SnapshotItem,
    SnapshotRejection,
    collection_digest,
    content_set_digest,
    normalize_asset,
    normalize_event_asset,
    normalize_link_asset,
    normalize_link_job,
)


def test_asset_normalization_omits_legacy_physical_path():
    result = normalize_asset({
        "id": 7, "sha256": "a" * 64, "size": 12, "path": "/private/legacy/file",
        "file_name": "clip.mp4", "mime": "video/mp4", "category": "video",
        "tag_status": "DONE", "tags_json": '[{"name":"sample"}]',
        "created_at": datetime(2026, 1, 1, tzinfo=UTC),
    })
    assert isinstance(result, SnapshotItem)
    assert result.source_key == f"asset:{'a' * 64}"
    assert "path" not in result.payload
    assert result.payload["tags"] == [{"name": "sample"}]


def test_invalid_asset_is_audited_instead_of_exported():
    result = normalize_asset({"id": 9, "sha256": "broken", "size": 1})
    assert isinstance(result, SnapshotRejection)
    assert result.reason_code == "INVALID_SHA256"


def test_link_normalization_omits_input_uri_and_feedback_target():
    result = normalize_link_job({
        "id": 4, "input_uri": "https://secret.example/item", "uri_sha256": "b" * 64,
        "feedback_target_json": '{"sender":"private"}', "link_kind": "HTTP",
        "strategy": "DIRECT", "source_type": "MESSAGE", "source_key": "message-4",
        "status": "COMPLETED", "expected_files": 1,
    })
    assert isinstance(result, SnapshotItem)
    assert "inputUri" not in result.payload
    assert "feedbackTarget" not in result.payload


def test_collection_digest_is_independent_of_read_order():
    first = SnapshotItem("ASSET", "2", "asset:b", {"value": 2})
    second = SnapshotItem("ASSET", "1", "asset:a", {"value": 1})
    assert collection_digest([first, second]) == collection_digest([second, first])


def test_link_asset_normalization_preserves_only_stable_relation():
    result = normalize_link_asset({"id": 5, "link_job_id": 2, "asset_id": 3,
                                   "source_key": "entry-1", "sha256": "c" * 64})
    assert isinstance(result, SnapshotItem)
    assert result.source_key == "link-asset:2:3"
    assert result.payload["contentSha256"] == "c" * 64


def test_event_asset_normalization_hashes_message_identity_and_omits_routing_fields():
    result = normalize_event_asset({
        "id": 8, "asset_id": 3, "sha256": "e" * 64, "source_index": 1,
        "platform": "telegram", "bot_account_id": "private-bot",
        "event_id": "private-event", "status": "COMPLETED",
        "platform_file_id": "private-file", "raw_payload": {"sender": "private"},
        "received_at": datetime(2026, 1, 1, tzinfo=UTC),
    })
    assert isinstance(result, SnapshotItem)
    assert result.item_type == "EVENT_ASSET"
    assert result.payload["sourceSystem"] == "DOWNLOADBOT_TELEGRAM"
    encoded = str(result.payload)
    assert "private-bot" not in encoded
    assert "private-event" not in encoded
    assert "private-file" not in encoded
    assert "sender" not in encoded


def test_event_asset_rejects_incomplete_event_identity():
    result = normalize_event_asset({"id": 9, "asset_id": 3, "sha256": "f" * 64,
                                    "source_index": 0, "platform": "qq"})
    assert isinstance(result, SnapshotRejection)
    assert result.reason_code == "MISSING_EVENT_IDENTITY"


def test_content_set_digest_ignores_executor_item_identity():
    first = {"itemId": "old-1", "fileName": "a.bin", "contentSha256": "d" * 64,
             "sizeBytes": 4}
    second = {"itemId": "new-9", "fileName": "a.bin", "contentSha256": "d" * 64,
              "sizeBytes": 4}
    assert content_set_digest([first]) == content_set_digest([second])
