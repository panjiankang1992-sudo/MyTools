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
