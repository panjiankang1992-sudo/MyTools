import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_migrate_legacy_state", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self):
        self.types = []
        self.imported = []

    def page(self, entity_type, owner_id, key):
        self.types.append(entity_type)
        if owner_id == 0:
            return {"items": [{"entityType": entity_type, "ownerId": 1,
                                "legacyKey": entity_type.lower(), "bookId": "book",
                                "payload": {"deleted": False}, "deleted": False,
                                "revision": 1, "serverUpdatedAt": 100}],
                    "nextAfterOwnerId": 1, "nextAfterKey": entity_type.lower(), "complete": True}
        raise AssertionError("Unexpected extra page")

    def import_batch(self, migration_key, dry_run, items):
        self.imported.extend(items)
        return {"accepted": len(items), "skipped": 0, "rejected": 0}


class ReaderMigrationTest(unittest.TestCase):
    def test_migrates_in_dependency_order_and_reports_digest(self):
        client = Client()
        result = MODULE.execute(client, "reader-v1", True)
        self.assertEqual(["SHELF", "PROGRESS", "MARKER"], client.types)
        self.assertEqual(3, result["exported"])
        self.assertEqual(3, result["accepted"])
        self.assertTrue(result["dryRun"])
        self.assertEqual(64, len(result["digestSha256"]))


if __name__ == "__main__":
    unittest.main()
