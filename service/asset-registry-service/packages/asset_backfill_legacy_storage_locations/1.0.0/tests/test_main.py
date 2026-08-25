"""Tests for legacy media Storage Gateway location backfill."""

import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("asset_backfill_legacy_storage_locations", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_selects_safe_media_location():
    """Only media below the configured legacy root becomes a managed location."""
    item = {"sourceSystem": "mytools", "legacyAssetId": "42", "asset": {
        "mimeType": "video/mp4", "location": {
            "storageUri": "file:///opt/extend/resource/big media/video.mp4"}}}
    result = MODULE.candidate(item, "/opt/extend/resource", "media")
    assert result["storageUri"] == "storage://media/big%20media/video.mp4"


def test_closes_dry_run_counts():
    """Dry-run resolves identities without mutating the registry."""
    item = {"sourceSystem": "mytools", "legacyAssetId": "42", "asset": {
        "mimeType": "image/jpeg", "location": {
            "storageUri": "file:///opt/extend/resource/images/a.jpg"}}}

    class Client:
        def page(self, snapshot_id, after_id):
            return {"snapshotId": snapshot_id, "items": [item], "nextAfterId": None}
        def resolve(self, identities):
            return {"mappings": [{**identities[0], "assetId": "asset-1"}]}
        def asset(self, asset_id):
            return {"version": 1, "locations": []}

    result = MODULE.execute(Client(), "snapshot", "/opt/extend/resource", "media", True)
    assert result["scanned"] == 1
    assert result["eligible"] == 1
    assert result["registered"] == 1
    assert result["rejected"] == 0
