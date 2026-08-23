import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_migrate_history", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self):
        self.imports = []

    def page(self, after_id):
        if after_id is None:
            return {"items": [{"legacyMessageId": "message-1"}], "nextAfterId": "message-1"}
        return {"items": [{"legacyMessageId": "message-2"}], "nextAfterId": None}

    def import_batch(self, migration_key, dry_run, items):
        self.imports.append((migration_key, dry_run, items))
        return {"dryRun": dry_run, "accepted": len(items), "skipped": 0,
                "rejected": 0, "digestSha256": ("a" if len(self.imports) == 1 else "b") * 64}


class MessageMigrateHistoryTest(unittest.TestCase):
    def test_pages_and_aggregates_dry_run_evidence(self):
        client = Client()
        result = MODULE.execute(client, "message-history-v1", True)
        self.assertTrue(result["dryRun"])
        self.assertEqual(2, result["exported"])
        self.assertEqual(2, result["accepted"])
        self.assertEqual("message-2", result["lastAfterId"])
        self.assertEqual(64, len(result["digestSha256"]))
        self.assertEqual(2, len(client.imports))

    def test_rejects_non_closing_import_counts(self):
        class InvalidClient(Client):
            def import_batch(self, migration_key, dry_run, items):
                return {"dryRun": dry_run, "accepted": 0, "skipped": 0,
                        "rejected": 0, "digestSha256": "a" * 64}

        with self.assertRaisesRegex(RuntimeError, "counts do not close"):
            MODULE.execute(InvalidClient(), "message-history-v1", False)


if __name__ == "__main__":
    unittest.main()
