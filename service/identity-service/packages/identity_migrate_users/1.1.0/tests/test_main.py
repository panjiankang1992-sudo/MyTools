"""冻结且幂等的 Identity 用户迁移测试。"""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("identity_migrate_v11", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def user(identifier: int) -> dict:
    """构造一个有效且不含真实敏感信息的夹具。"""
    return {"id": identifier, "externalUserId": f"mytools:{identifier}",
            "username": f"user-{identifier}", "email": None,
            "passwordHash": "$2a$10$" + "x" * 53, "status": "ACTIVE",
            "credentialVersion": 0, "roles": ["USER"]}


class Client:
    """确定性的冻结来源和目标桩。"""

    def __init__(self):
        self.calls = []

    def page(self, after_id, high_water):
        """返回一个特意未按身份排序的来源页。"""
        if after_id == 0:
            return {"users": [user(2), user(1)], "nextAfterId": 2,
                    "complete": True, "snapshotHighWater": 2}
        raise AssertionError("unexpected page")

    def import_batch(self, migration_key, dry_run, users):
        """捕获规范化批次并返回闭合计数。"""
        self.calls.append((migration_key, dry_run, users))
        return {"migrationKey": migration_key, "dryRun": dry_run,
                "exported": len(users), "accepted": len(users), "skipped": 0,
                "rejected": 0, "digestSha256": "a" * 64}


class IdentityMigrationTest(unittest.TestCase):
    """验证证据、冻结边界和敏感信息边界。"""

    def test_dry_run_uses_frozen_ordered_batch_and_safe_report(self):
        client = Client()
        result = MODULE.execute(client, "identity-users-2026", True)
        self.assertTrue(result["dryRun"])
        self.assertEqual([1, 2], [value["id"] for value in client.calls[0][2]])
        self.assertEqual(result["digestSha256"], result["sourceDigestSha256"])
        self.assertEqual(2, result["sourceItemCount"])
        self.assertNotIn("passwordHash", result)

    def test_digest_is_independent_of_source_insert_order(self):
        self.assertEqual(MODULE.collection_digest([user(1), user(2)]),
                         MODULE.collection_digest([user(2), user(1)]))

    def test_digest_matches_identity_service_protocol(self):
        value = user(10)
        value.update({"username": "fixture", "email": "fixture@example.com",
                      "roles": ["USER", "ADMIN"]})
        self.assertEqual("345a1029ff2e504410b823845302624026cfb86ab74f6857af2f3c571b9b6801",
                         MODULE.collection_digest([value]))

    def test_rejects_changed_high_water(self):
        client = Client()
        original = client.page
        client.page = lambda after, high: ({"users": [user(1)], "nextAfterId": 1,
                                            "complete": False, "snapshotHighWater": 2}
                                           if after == 0 else
                                           {"users": [], "nextAfterId": 1,
                                            "complete": True, "snapshotHighWater": 3})
        with self.assertRaisesRegex(RuntimeError, "high water changed"):
            MODULE.execute(client, "identity-users-2026", False)
        client.page = original

    def test_reuses_explicit_dry_run_high_water(self):
        client = Client()
        result = MODULE.execute(client, "identity-users-2026", False, 2)
        self.assertEqual(2, result["sourceHighWater"])


if __name__ == "__main__":
    unittest.main()
