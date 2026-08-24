from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("downloadbot_capture_snapshot", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_asset_normalizer_does_not_export_physical_path():
    legacy_id, source_key, payload = MODULE.normalize_asset({
        "id": 1, "sha256": "a" * 64, "size": 3, "path": "/legacy/private",
        "file_name": "a.bin", "mime": "application/octet-stream"})
    assert legacy_id == "1"
    assert source_key == f"asset:{'a' * 64}"
    assert "path" not in payload


def test_link_job_normalizer_does_not_export_url_or_feedback_route():
    _, _, payload = MODULE.normalize_link_job({
        "id": 2, "uri_sha256": "b" * 64, "input_uri": "https://private.invalid",
        "feedback_target_json": '{"recipient":"private"}'})
    assert "inputUri" not in payload
    assert "feedbackTarget" not in payload


def test_link_asset_rejects_invalid_checksum():
    with pytest.raises(ValueError, match="INVALID_SHA256"):
        MODULE.normalize_link_asset({"id": 3, "link_job_id": 1, "asset_id": 2,
                                     "sha256": "z" * 64})


def test_ingress_event_preserves_raw_payload_and_full_identity():
    legacy_id, source_key, payload = MODULE.normalize_ingress_event({
        "id": 4, "platform": "telegram", "bot_account_id": "bot-private",
        "event_id": "event-private", "raw_payload": '{"text":"keep me"}',
        "status": "COMPLETED", "processing_stage": "DONE"})
    assert legacy_id == "4"
    assert source_key.startswith("event:")
    assert payload["botAccountId"] == "bot-private"
    assert payload["eventId"] == "event-private"
    assert payload["rawPayload"] == {"text": "keep me"}


def test_message_preserves_identifiers_and_event_relation():
    legacy_id, _, payload = MODULE.normalize_message({
        "message_row_id": 5, "event_row_id": 4, "platform": "qq",
        "bot_account_id": "account", "event_id": "event",
        "platform_message_id": "message", "conversation_id": "conversation",
        "sender_id": "sender"})
    assert legacy_id == "5"
    assert payload["legacyEventRowId"] == "4"
    assert payload["platformMessageId"] == "message"
    assert payload["conversationId"] == "conversation"
    assert payload["senderId"] == "sender"


def test_event_asset_normalizer_hashes_identity_and_omits_private_fields():
    _, source_key, payload = MODULE.normalize_event_asset({
        "id": 4, "asset_id": 2, "sha256": "c" * 64, "source_index": 0,
        "platform": "onebot", "bot_account_id": "private-account",
        "event_id": "private-event", "platform_file_id": "private-file",
        "status": "COMPLETED"})
    assert source_key.startswith("event-asset:")
    encoded = str(payload)
    assert "private-account" not in encoded
    assert "private-event" not in encoded
    assert "private-file" not in encoded
