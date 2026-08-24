"""Offline legacy Asset migration cutover gate tests."""

import copy
import unittest

from service.scripts.asset_cutover_gate import evaluate


def migration(dry_run: bool, accepted: int, skipped: int) -> dict:
    """Build one valid mapping migration report."""

    return {"migrationKey": "asset-2026", "sourceSnapshotId": "snapshot-2026",
            "dryRun": dry_run, "exported": 3, "accepted": accepted,
            "skipped": skipped, "rejected": 0, "digestSha256": "b" * 64,
            "lastAfterId": "cursor"}


class AssetCutoverGateTest(unittest.TestCase):
    """Cover closed evidence and important rejection paths."""

    def setUp(self):
        self.snapshot = {"snapshotId": "snapshot-2026", "ownerId": 7, "highWaterId": 9,
                         "captured": 3, "rejected": 1, "digestSha256": "a" * 64}
        self.dry_run = migration(True, 3, 0)
        self.applied = migration(False, 3, 0)
        self.replay = migration(False, 0, 3)
        self.target = {"migrationKey": "asset-2026", "sourceSnapshotId": "snapshot-2026",
                       "itemCount": 3, "collectionSha256": "b" * 64}

    def test_accepts_declared_source_rejection_and_closed_mapping_evidence(self):
        result = evaluate(self.snapshot, self.dry_run, self.applied, self.replay,
                          self.target, 1)
        self.assertTrue(result["ready"])

    def test_rejects_undeclared_source_rejection(self):
        result = evaluate(self.snapshot, self.dry_run, self.applied, self.replay,
                          self.target, 0)
        self.assertIn("SOURCE_REJECTION_COUNT_MISMATCH", result["errors"])

    def test_rejects_non_idempotent_replay(self):
        replay = copy.deepcopy(self.replay)
        replay.update({"accepted": 1, "skipped": 2})
        self.assertIn("REPLAY_NOT_IDEMPOTENT",
                      evaluate(self.snapshot, self.dry_run, self.applied, replay,
                               self.target, 1)["errors"])

    def test_rejects_target_digest_mismatch(self):
        target = copy.deepcopy(self.target)
        target["collectionSha256"] = "c" * 64
        self.assertIn("TARGET_DIGEST_MISMATCH",
                      evaluate(self.snapshot, self.dry_run, self.applied, self.replay,
                               target, 1)["errors"])


if __name__ == "__main__":
    unittest.main()
