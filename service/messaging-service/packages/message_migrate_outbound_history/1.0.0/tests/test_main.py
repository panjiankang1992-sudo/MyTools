"""Frozen outbound history migration task tests."""

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_migrate_outbound_history", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self, drift=False, target_digest="a" * 64):
        self.calls = 0
        self.drift = drift
        self.target_digest = target_digest

    def page(self, after_id, high_water):
        self.calls += 1
        digest = ("b" if self.drift and self.calls == 2 else "a") * 64
        common = {"snapshotHighWater": "Mg", "itemCount": 2,
                  "collectionSha256": digest}
        if after_id is None:
            return {**common, "items": [{"legacyMessageId": "out-1"}],
                    "nextAfterId": "MQ"}
        return {**common, "items": [{"legacyMessageId": "out-2"}],
                "nextAfterId": None}

    def import_batch(self, _migration_key, dry_run, items):
        return {"dryRun": dry_run, "accepted": len(items), "skipped": 0,
                "rejected": 0, "digestSha256": "c" * 64}

    def evidence(self, migration_key):
        return {"migrationKey": migration_key, "itemCount": 2,
                "collectionSha256": self.target_digest}


class OutboundMigrationTest(unittest.TestCase):
    def test_freezes_and_reconciles_outbound_archive(self):
        result = MODULE.execute(Client(), "outbound-v1", False)
        self.assertEqual(2, result["sourceItemCount"])
        self.assertEqual("a" * 64, result["sourceDigestSha256"])

    def test_rejects_source_drift(self):
        with self.assertRaisesRegex(RuntimeError, "changed during migration"):
            MODULE.execute(Client(drift=True), "outbound-v1", True)

    def test_rejects_target_mismatch(self):
        with self.assertRaisesRegex(RuntimeError, "target reconciliation"):
            MODULE.execute(Client(target_digest="b" * 64), "outbound-v1", False)


if __name__ == "__main__":
    unittest.main()
