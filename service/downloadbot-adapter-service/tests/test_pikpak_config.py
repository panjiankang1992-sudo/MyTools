from pathlib import Path
import pytest
from mytools_downloadbot_adapter.pikpak_config import LegacyPikPakConfigExporter

def test_exporter_returns_only_safe_stable_metadata(tmp_path):
    config = tmp_path / "config.yaml"
    config.write_text("""
link_download:
  pikpak_offline_dir: DownloadBot/offline
pikpak:
  - id: pikpak-main
    enabled: true
    remote_name: pikpak
    watch_dir: DownloadBot/inbox
    backup_dir: DownloadBot/processed
    rclone_config: /private/rclone.conf
    proxy_url: http://secret-proxy
    settle_seconds: 60
""", encoding="utf-8")
    page = LegacyPikPakConfigExporter(str(config)).export_page(None, 50)
    assert page["totalCount"] == 1
    assert page["items"][0] == {"externalKey": "pikpak-main", "remoteKey": "pikpak",
        "offlineRoot": "DownloadBot/offline", "readyRoot": "DownloadBot/inbox",
        "legacyEnabled": True, "stableSeconds": 60}
    text = str(page)
    assert "rclone.conf" not in text and "secret-proxy" not in text and "backup" not in text

def test_exporter_requires_absolute_source_path():
    with pytest.raises(ValueError, match="absolute"):
        LegacyPikPakConfigExporter("config.yaml")

def test_exporter_rejects_unrepresentable_stable_window(tmp_path):
    config = tmp_path / "config.yaml"
    config.write_text("""
pikpak:
  - id: main
    watch_dir: DownloadBot/inbox
    settle_seconds: 86401
""", encoding="utf-8")
    with pytest.raises(ValueError, match="stable window"):
        LegacyPikPakConfigExporter(str(config)).export_page(None, 50)
