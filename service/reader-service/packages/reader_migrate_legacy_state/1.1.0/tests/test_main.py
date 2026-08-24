"""Frozen Reader state migration task tests."""

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_migrate_legacy_state_v11", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def item(entity_type: str) -> dict:
    """Build one valid exported item."""

    return {"entityType": entity_type, "ownerId": 1,
            "legacyKey": entity_type.lower(), "bookId": "book",
            "payload": {"deleted": False}, "deleted": False,
            "revision": 1, "serverUpdatedAt": 100}


class Client:
    def __init__(self, drift=False, target_digest=None):
        self.types = []
        self.imported = []
        self.drift = drift
        self.target_digest = target_digest

    def page(self, entity_type, owner_id, key, high_water):
        self.types.append(entity_type)
        cursor = {"ownerId": 1, "key": entity_type.lower()}
        if self.drift and high_water is not None:
            cursor["ownerId"] = 2
        return {"items": [item(entity_type)], "nextAfterOwnerId": 1,
                "nextAfterKey": entity_type.lower(), "complete": True,
                "snapshotOwnerId": cursor["ownerId"], "snapshotKey": cursor["key"]}

    def import_batch(self, migration_key, dry_run, items):
        self.imported.extend(items)
        return {"accepted": len(items), "skipped": 0, "rejected": 0,
                "digestSha256": "a" * 64}

    def evidence(self, migration_key):
        return {"migrationKey": migration_key, "itemCount": 3,
                "digestSha256": self.target_digest}


class ReaderMigrationTest(unittest.TestCase):
    def test_freezes_source_and_reconciles_target(self):
        client = Client()
        dry_run = MODULE.execute(client, "reader-v1", True)
        client.target_digest = dry_run["digestSha256"]
        applied = MODULE.execute(client, "reader-v1", False, dry_run["sourceHighWater"])
        self.assertEqual(["SHELF", "PROGRESS", "MARKER"] * 2, client.types)
        self.assertEqual(3, applied["exported"])
        self.assertEqual(dry_run["digestSha256"], applied["digestSha256"])

    def test_rejects_changed_high_water(self):
        client = Client(drift=True)
        high_water = {name: {"ownerId": 1, "key": name.lower()}
                      for name in MODULE.ENTITY_TYPES}
        with self.assertRaisesRegex(RuntimeError, "high water changed"):
            MODULE.execute(client, "reader-v1", True, high_water)

    def test_rejects_target_digest_mismatch(self):
        with self.assertRaisesRegex(RuntimeError, "target reconciliation"):
            MODULE.execute(Client(target_digest="b" * 64), "reader-v1", False)


if __name__ == "__main__":
    unittest.main()
