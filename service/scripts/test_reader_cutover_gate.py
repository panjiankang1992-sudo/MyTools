"""Offline Reader migration cutover gate tests."""

import copy
import unittest

from service.scripts.reader_cutover_gate import evaluate


HIGH_WATER = {name: {"ownerId": 7, "key": name.lower()}
              for name in ("SHELF", "PROGRESS", "MARKER")}


def report(dry_run: bool, accepted: int, skipped: int) -> dict:
    """Build one valid migration report fixture."""

    return {"migrationKey": "reader-state-2026", "dryRun": dry_run,
            "exported": 4, "accepted": accepted, "skipped": skipped, "rejected": 0,
            "digestSha256": "a" * 64, "sourceHighWater": copy.deepcopy(HIGH_WATER)}


class ReaderCutoverGateTest(unittest.TestCase):
    def setUp(self):
        self.dry_run = report(True, 4, 0)
        self.applied = report(False, 4, 0)
        self.replay = report(False, 0, 4)
        self.target = {"migrationKey": "reader-state-2026", "itemCount": 4,
                       "digestSha256": "a" * 64}

    def test_accepts_closed_idempotent_evidence(self):
        self.assertTrue(evaluate(self.dry_run, self.applied, self.replay, self.target)["ready"])

    def test_rejects_source_high_water_drift(self):
        replay = copy.deepcopy(self.replay)
        replay["sourceHighWater"]["PROGRESS"]["ownerId"] = 8
        self.assertIn("SOURCE_EVIDENCE_MISMATCH",
                      evaluate(self.dry_run, self.applied, replay, self.target)["errors"])

    def test_rejects_non_idempotent_replay(self):
        replay = copy.deepcopy(self.replay)
        replay.update({"accepted": 1, "skipped": 3})
        self.assertIn("REPLAY_NOT_IDEMPOTENT",
                      evaluate(self.dry_run, self.applied, replay, self.target)["errors"])

    def test_rejects_target_digest_mismatch(self):
        target = copy.deepcopy(self.target)
        target["digestSha256"] = "b" * 64
        self.assertIn("TARGET_DIGEST_MISMATCH",
                      evaluate(self.dry_run, self.applied, self.replay, target)["errors"])


if __name__ == "__main__":
    unittest.main()
