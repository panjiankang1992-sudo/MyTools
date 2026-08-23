import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("storage_cutover_gate.py")
SPEC = importlib.util.spec_from_file_location("storage_cutover_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def migration(dry_run):
    return {"migrationKey": "storage-v1", "dryRun": dry_run, "exported": 2, "accepted": 2,
            "bound": 0 if dry_run else 2, "rejected": 0, "digestSha256": "a" * 64,
            "lastAfterId": "cursor"}


def reconciliation(matched=True):
    return {"migrationKey": "storage-v1",
            "accountId": "00000000-0000-4000-8000-000000000001",
            "storageProviderId": "00000000-0000-4000-8000-000000000002",
            "storageOperationId": "00000000-0000-4000-8000-000000000003", "matched": matched,
            "mismatchReasons": [] if matched else ["CONTENT_MISMATCH"],
            "driveItemCount": 3, "storageItemCount": 3,
            "driveContentSha256": "b" * 64, "storageContentSha256": "b" * 64}


class StorageCutoverGateTest(unittest.TestCase):
    def test_allows_matching_migration_and_reconciliation_evidence(self):
        result = MODULE.evaluate(migration(True), migration(False), [reconciliation()], 1)
        self.assertTrue(result["ready"])
        self.assertEqual([], result["errors"])
        self.assertNotIn("00000000-0000-4000", str(result))
        self.assertNotIn("b" * 64, str(result))

    def test_rejects_changed_source_and_incomplete_binding(self):
        applied = migration(False)
        applied["digestSha256"] = "c" * 64
        applied["bound"] = 1
        result = MODULE.evaluate(migration(True), applied, [reconciliation()], 1)
        self.assertFalse(result["ready"])
        self.assertIn("SOURCE_DIGEST_MISMATCH", result["errors"])
        self.assertIn("MIGRATION_BINDING_INCOMPLETE", result["errors"])

    def test_rejects_missing_or_failed_reconciliation(self):
        result = MODULE.evaluate(migration(True), migration(False), [reconciliation(False)], 2)
        self.assertFalse(result["ready"])
        self.assertIn("RECONCILIATION_COUNT_MISMATCH", result["errors"])
        self.assertIn("RECONCILIATION_FAILED", result["errors"])

    def test_rejects_internally_inconsistent_matched_report(self):
        report = reconciliation()
        report["storageContentSha256"] = "c" * 64
        result = MODULE.evaluate(migration(True), migration(False), [report], 1)
        self.assertFalse(result["ready"])
        self.assertIn("RECONCILIATION_FAILED", result["errors"])


if __name__ == "__main__":
    unittest.main()
