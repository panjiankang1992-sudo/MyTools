"""离线 Identity 用户切换门禁测试。"""

import copy
import unittest

from service.scripts.identity_cutover_gate import evaluate


def report(dry_run: bool, accepted: int, skipped: int) -> dict:
    """构造一个有效的迁移报告夹具。"""
    return {"migrationKey": "identity-users-2026", "dryRun": dry_run,
            "exported": 3, "accepted": accepted, "skipped": skipped, "rejected": 0,
            "digestSha256": "a" * 64, "sourceItemCount": 3,
            "sourceDigestSha256": "a" * 64, "sourceHighWater": 9}


class IdentityCutoverGateTest(unittest.TestCase):
    """覆盖安全与不安全的切换证据组合。"""

    def setUp(self):
        self.dry_run = report(True, 3, 0)
        self.applied = report(False, 3, 0)
        self.replay = report(False, 0, 3)
        self.target = {"migrationKey": "identity-users-2026", "itemCount": 3,
                       "collectionSha256": "a" * 64}

    def test_accepts_closed_idempotent_evidence(self):
        self.assertTrue(evaluate(self.dry_run, self.applied, self.replay,
                                 self.target)["ready"])

    def test_rejects_source_drift(self):
        replay = copy.deepcopy(self.replay)
        replay["sourceHighWater"] = 10
        self.assertIn("SOURCE_EVIDENCE_MISMATCH",
                      evaluate(self.dry_run, self.applied, replay, self.target)["errors"])

    def test_rejects_non_idempotent_replay(self):
        replay = copy.deepcopy(self.replay)
        replay.update({"accepted": 1, "skipped": 2})
        self.assertIn("REPLAY_NOT_IDEMPOTENT",
                      evaluate(self.dry_run, self.applied, replay, self.target)["errors"])

    def test_rejects_target_digest_mismatch(self):
        target = copy.deepcopy(self.target)
        target["collectionSha256"] = "b" * 64
        self.assertIn("TARGET_DIGEST_MISMATCH",
                      evaluate(self.dry_run, self.applied, self.replay, target)["errors"])


if __name__ == "__main__":
    unittest.main()
