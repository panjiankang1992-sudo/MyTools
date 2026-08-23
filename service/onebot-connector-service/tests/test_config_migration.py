from pathlib import Path

import pytest

from mytools_onebot_connector.config_migration import build_manifest


def write_config(tmp_path: Path, value: str) -> Path:
    path = tmp_path / "config.yaml"
    path.write_text(value, encoding="utf-8")
    return path


def test_manifest_migrates_only_routes_and_forces_accounts_disabled(tmp_path: Path):
    manifest = build_manifest(write_config(tmp_path, """
onebot:
  - id: qq-main
    enabled: true
    token_env: DOWNLOADBOT_ONEBOT_TOKEN
    http_base_url: http://127.0.0.1:3000
    host_qq_root: /opt/napcat/qq
    container_qq_root: /app/.config/QQ
"""))
    assert manifest["summary"] == {"accepted": 1, "rejected": 0}
    assert manifest["accounts"] == [{
        "externalKey": "qq-main", "httpBaseUrl": "http://127.0.0.1:3000",
        "secretRef": "env://DOWNLOADBOT_ONEBOT_TOKEN", "hostQqRoot": "/opt/napcat/qq",
        "containerQqRoot": "/app/.config/QQ", "enabled": False}]
    assert "DOWNLOADBOT_ONEBOT_TOKEN" not in str(manifest).replace("env://DOWNLOADBOT_ONEBOT_TOKEN", "")


def test_manifest_rejects_unsafe_and_duplicate_accounts_without_partial_apply(tmp_path: Path):
    manifest = build_manifest(write_config(tmp_path, """
onebot:
  - id: duplicated
    token_env: TOKEN_ONE
  - id: duplicated
    token_env: TOKEN_TWO
  - id: remote
    token_env: TOKEN_THREE
    http_base_url: https://provider.example.test
"""))
    assert manifest["summary"] == {"accepted": 1, "rejected": 2}
    assert [item["index"] for item in manifest["rejected"]] == [1, 2]


def test_manifest_rejects_missing_token_reference(tmp_path: Path):
    manifest = build_manifest(write_config(tmp_path, "onebot:\n  - id: missing\n"))
    assert manifest["summary"] == {"accepted": 0, "rejected": 1}
