import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("messaging_cutover_gate.py")
SPEC = importlib.util.spec_from_file_location("messaging_cutover_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
DIGEST = "a" * 64
RESULT_DIGEST = "b" * 64


def migration(dry_run, accepted=2, skipped=0):
    return {"migrationKey": "messages-v1", "dryRun": dry_run, "exported": 2,
            "accepted": accepted, "skipped": skipped, "rejected": 0,
            "digestSha256": RESULT_DIGEST, "sourceItemCount": 2,
            "sourceDigestSha256": DIGEST, "sourceHighWater": "TWc"}


def target(count=2, digest=DIGEST):
    return {"migrationKey": "messages-v1", "itemCount": count,
            "collectionSha256": digest}


class MessagingCutoverGateTest(unittest.TestCase):

    def test_accepts_frozen_applied_replayed_and_reconciled_evidence(self):
        result = MODULE.evaluate(migration(True), migration(False),
                                 migration(False, 0, 2), target())
        self.assertTrue(result["ready"])
        self.assertEqual([], result["errors"])

    def test_rejects_source_drift(self):
        applied = migration(False)
        applied["sourceHighWater"] = "different"
        result = MODULE.evaluate(migration(True), applied,
                                 migration(False, 0, 2), target())
        self.assertFalse(result["ready"])
        self.assertIn("SOURCE_EVIDENCE_MISMATCH", result["errors"])

    def test_rejects_non_idempotent_replay(self):
        result = MODULE.evaluate(migration(True), migration(False),
                                 migration(False), target())
        self.assertFalse(result["ready"])
        self.assertIn("REPLAY_NOT_IDEMPOTENT", result["errors"])

    def test_rejects_target_count_or_digest_mismatch(self):
        result = MODULE.evaluate(migration(True), migration(False),
                                 migration(False, 0, 2), target(1, "c" * 64))
        self.assertFalse(result["ready"])
        self.assertIn("TARGET_COUNT_MISMATCH", result["errors"])
        self.assertIn("TARGET_DIGEST_MISMATCH", result["errors"])


if __name__ == "__main__":
    unittest.main()
