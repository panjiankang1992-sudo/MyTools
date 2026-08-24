"""Tests for reconciled legacy asset mapping migration."""

import importlib.util
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("asset_migrate_legacy_mappings_v11", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def item(legacy_id: str) -> dict:
    """Build one normalized snapshot item."""

    return {"sourceSystem": "MyTools", "legacyAssetId": legacy_id,
            "asset": {"ownerId": 7, "idempotencyKey": f"legacy:{legacy_id}",
                      "sourceType": "LEGACY_ASSET", "sourceBusinessId": legacy_id,
                      "contentSha256": legacy_id[-1] * 64, "sizeBytes": 20,
                      "mimeType": "application/octet-stream",
                      "location": {"idempotencyKey": f"location:{legacy_id}",
                                   "providerType": "LEGACY_LOCAL",
                                   "storageUri": f"file:///legacy/{legacy_id}",
                                   "providerVersion": "v1"}},
            "mediaMetadata": {"tags": [{"name": "manual"}]}}


class Client:
    """Provide two pages and independently recomputed target evidence."""

    def __init__(self):
        self.imported = []
        self.pages = iter([
            {"snapshotId": "snapshot-1", "items": [item("20")], "nextAfterId": "cursor"},
            {"snapshotId": "snapshot-1", "items": [item("3")], "nextAfterId": None}])

    def page(self, _snapshot, _after):
        return next(self.pages)

    def import_batch(self, _key, _snapshot, dry_run, items):
        self.imported.extend(items)
        digest = module.hashlib.sha256()
        for value in items:
            module.update_digest(digest, module.item_digest(value))
        return {"dryRun": dry_run, "accepted": len(items), "skipped": 0, "rejected": 0,
                "digestSha256": digest.hexdigest()}

    def evidence(self, migration_key, snapshot):
        identities = [(value["sourceSystem"], value["legacyAssetId"], module.item_digest(value))
                      for value in self.imported]
        return {"migrationKey": migration_key, "sourceSnapshotId": snapshot,
                "itemCount": len(identities),
                "collectionSha256": module.collection_digest(identities)}


def test_reconciles_target_independent_of_source_page_order():
    """Formal result must use the same stable identity ordering as target evidence."""

    client = Client()
    result = module.execute(client, "asset-2026", "snapshot-1", False)

    assert result["exported"] == 2
    assert result["accepted"] == 2
    assert result["lastAfterId"] == "cursor"
    assert all("mediaMetadata" not in value for value in client.imported)


def test_rejects_target_digest_mismatch():
    """A successful HTTP import is insufficient when target evidence differs."""

    client = Client()
    client.evidence = lambda key, snapshot: {"migrationKey": key, "sourceSnapshotId": snapshot,
                                              "itemCount": 2, "collectionSha256": "f" * 64}
    with pytest.raises(RuntimeError, match="target reconciliation"):
        module.execute(client, "asset-2026", "snapshot-1", False)


def test_rejects_incomplete_mapping_payload():
    """Missing stable asset fields must fail before the target call."""

    with pytest.raises(RuntimeError, match="payload is invalid"):
        module.item_digest({"sourceSystem": "MyTools", "legacyAssetId": "1",
                            "asset": {"ownerId": 7}})
