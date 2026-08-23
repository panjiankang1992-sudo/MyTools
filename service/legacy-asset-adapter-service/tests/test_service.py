import importlib.util
from pathlib import Path

import pytest

from mytools_legacy_asset_adapter.service import ExportService


class Repository:
    def __init__(self, values):
        self.values = values

    def page(self, _snapshot_id, after_sequence, limit):
        return [item for item in self.values if item["sequenceId"] > after_sequence][:limit]


def item(sequence):
    return {"sequenceId": sequence, "payload": {"sourceSystem": "MyTools",
            "legacyAssetId": str(sequence), "asset": {"ownerId": 0}}}


def test_export_is_default_off():
    with pytest.raises(PermissionError, match="export is disabled"):
        ExportService(Repository([]), False).page("snapshot-1", None, 200)


def test_exports_stable_bounded_pages():
    service = ExportService(Repository([item(1), item(2), item(3)]), True)
    first = service.page("snapshot-1", None, 2)
    second = service.page("snapshot-1", first["nextAfterId"], 2)
    assert [value["legacyAssetId"] for value in first["items"]] == ["1", "2"]
    assert [value["legacyAssetId"] for value in second["items"]] == ["3"]
    assert second["nextAfterId"] is None


def test_rejects_invalid_snapshot_and_limit():
    service = ExportService(Repository([]), True)
    with pytest.raises(ValueError, match="snapshotId is invalid"):
        service.page("../unsafe", None, 200)
    with pytest.raises(ValueError, match="limit is invalid"):
        service.page("snapshot-1", None, 201)


def test_asset_migration_script_consumes_explicit_snapshot_contract():
    service = ExportService(Repository([item(1), item(2)]), True)
    script = (Path(__file__).parents[2] / "asset-registry-service" / "packages" /
              "asset_migrate_legacy_mappings" / "1.0.0" / "scripts" / "main.py")
    spec = importlib.util.spec_from_file_location("legacy_asset_adapter_contract", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    class Client:
        def page(self, snapshot_id, after_id):
            return service.page(snapshot_id, after_id, 200)

        def import_batch(self, _migration_key, source_snapshot_id, dry_run, items):
            assert source_snapshot_id == "snapshot-1"
            return {"dryRun": dry_run, "accepted": len(items), "skipped": 0, "rejected": 0,
                    "digestSha256": "c" * 64}

    result = module.execute(Client(), "asset-migration-1", "snapshot-1", True)
    assert result["exported"] == 2
    assert result["sourceSnapshotId"] == "snapshot-1"
