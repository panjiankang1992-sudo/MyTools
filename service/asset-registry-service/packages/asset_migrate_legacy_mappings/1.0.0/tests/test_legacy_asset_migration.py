import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("asset_migrate_legacy_mappings", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self, pages):
        self.pages = iter(pages)

    def page(self, _after_id):
        return next(self.pages)

    def import_batch(self, _migration_key, _source_snapshot_id, dry_run, items):
        return {"dryRun": dry_run, "accepted": len(items), "skipped": 0, "rejected": 0,
                "digestSha256": "b" * 64}


def test_aggregates_pages_and_target_evidence():
    client = FakeClient([{"snapshotId": "snapshot-1", "items": [{"legacyAssetId": "1"}],
                          "nextAfterId": "cursor-1"},
                         {"snapshotId": "snapshot-1", "items": [{"legacyAssetId": "2"}],
                          "nextAfterId": None}])
    result = MODULE.execute(client, "asset-migration-1", True)
    assert result["exported"] == 2
    assert result["accepted"] == 2
    assert result["lastAfterId"] == "cursor-1"
    assert result["sourceSnapshotId"] == "snapshot-1"


def test_rejects_non_advancing_cursor_and_unclosed_counts():
    with pytest.raises(RuntimeError, match="did not advance"):
        MODULE.execute(FakeClient([{"snapshotId": "snapshot-2", "items": [], "nextAfterId": "same"}]),
                       "asset-migration-2", True, "same")

    class InvalidClient(FakeClient):
        def import_batch(self, _migration_key, _source_snapshot_id, dry_run, _items):
            return {"dryRun": dry_run, "accepted": 0, "skipped": 0, "rejected": 0,
                    "digestSha256": "b" * 64}

    with pytest.raises(RuntimeError, match="counts do not close"):
        MODULE.execute(InvalidClient([{"snapshotId": "snapshot-3", "items": [{}], "nextAfterId": None}]),
                       "asset-migration-3", False)


def test_rejects_source_snapshot_change():
    pages = [{"snapshotId": "snapshot-a", "items": [], "nextAfterId": "cursor-1"},
             {"snapshotId": "snapshot-b", "items": [], "nextAfterId": None}]
    with pytest.raises(RuntimeError, match="snapshot changed"):
        MODULE.execute(FakeClient(pages), "asset-migration-4", True)
