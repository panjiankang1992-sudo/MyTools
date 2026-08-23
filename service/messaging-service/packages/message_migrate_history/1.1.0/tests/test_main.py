import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_migrate_history_v11", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self, drift=False):
        self.calls = 0
        self.drift = drift

    def page(self, after_id, high_water):
        self.calls += 1
        digest = ("b" if self.drift and self.calls == 2 else "a") * 64
        common = {"snapshotHighWater": "Mg", "itemCount": 2,
                  "collectionSha256": digest}
        if after_id is None:
            return {**common, "items": [{"legacyMessageId": "message-1"}],
                    "nextAfterId": "MQ"}
        return {**common, "items": [{"legacyMessageId": "message-2"}],
                "nextAfterId": None}

    def import_batch(self, _migration_key, dry_run, items):
        return {"dryRun": dry_run, "accepted": len(items), "skipped": 0,
                "rejected": 0, "digestSha256": "c" * 64}


class MessageMigrateHistoryV11Test(unittest.TestCase):

    def test_freezes_source_evidence_across_pages(self):
        result = MODULE.execute(Client(), "message-history-v1", True)
        self.assertEqual(2, result["sourceItemCount"])
        self.assertEqual("a" * 64, result["sourceDigestSha256"])
        self.assertEqual("Mg", result["sourceHighWater"])

    def test_rejects_source_drift(self):
        with self.assertRaisesRegex(RuntimeError, "changed during migration"):
            MODULE.execute(Client(True), "message-history-v1", False)


if __name__ == "__main__":
    unittest.main()
