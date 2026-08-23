from datetime import UTC, datetime

from mytools_downloadbot_adapter.snapshot import (
    SnapshotItem,
    SnapshotRejection,
    collection_digest,
    normalize_asset,
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
