import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("download_cutover_gate.py")
SPEC = importlib.util.spec_from_file_location("download_cutover_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SNAPSHOT_ID = "00000000-0000-4000-8000-000000000001"
REQUEST_ID = "00000000-0000-4000-8000-000000000002"
DIGEST = "a" * 64


def snapshot(rejections=0):
    return {"snapshotId": SNAPSHOT_ID, "status": "SEALED", "itemCount": 2,
            "rejectionCount": rejections, "collectionSha256": DIGEST}


def migration(dry_run, accepted=2, skipped=0):
    return {"migrationKey": "download-v1", "sourceSnapshotId": SNAPSHOT_ID,
            "dryRun": dry_run, "exported": 2, "accepted": accepted,
            "skipped": skipped, "rejected": 0, "digestSha256": DIGEST}


def reconciliation(matched=True):
    reasons = [] if matched else ["CONTENT_SET_MISMATCH"]
    return {"sourceSnapshotId": SNAPSHOT_ID, "eventId": "event-1",
            "downloadRequestId": REQUEST_ID, "matched": matched, "mismatchReasons": reasons,
            "legacy": {"legacyJobId": "job-1", "legacyStatus": "COMPLETED",
                       "itemCount": 1, "totalBytes": 10, "contentSetSha256": DIGEST},
            "current": {"status": "SUCCEEDED", "itemCount": 1, "totalBytes": 10,
                        "contentSetSha256": DIGEST}}


class DownloadCutoverGateTest(unittest.TestCase):

    def test_accepts_frozen_matching_and_idempotent_evidence(self):
        result = MODULE.evaluate(snapshot(), migration(True), migration(False),
                                 migration(False, 0, 2), [reconciliation()], 1)
        self.assertTrue(result["ready"])
        self.assertEqual([], result["errors"])
        self.assertEqual(2, result["migratedCount"])

    def test_rejects_snapshot_rejections_and_source_drift(self):
        applied = migration(False)
        applied["digestSha256"] = "b" * 64
        result = MODULE.evaluate(snapshot(1), migration(True), applied,
                                 migration(False, 0, 2), [], 0)
        self.assertFalse(result["ready"])
        self.assertIn("SNAPSHOT_HAS_REJECTIONS", result["errors"])
        self.assertIn("SOURCE_DIGEST_MISMATCH", result["errors"])

    def test_rejects_non_idempotent_replay(self):
        result = MODULE.evaluate(snapshot(), migration(True), migration(False),
                                 migration(False), [reconciliation()], 1)
        self.assertFalse(result["ready"])
        self.assertIn("REPLAY_NOT_IDEMPOTENT", result["errors"])

    def test_rejects_missing_or_failed_reconciliation(self):
        result = MODULE.evaluate(snapshot(), migration(True), migration(False),
                                 migration(False, 0, 2), [reconciliation(False)], 2)
        self.assertFalse(result["ready"])
        self.assertIn("RECONCILIATION_COUNT_MISMATCH", result["errors"])
        self.assertIn("RECONCILIATION_FAILED", result["errors"])


if __name__ == "__main__":
    unittest.main()
