import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("run_migration_plan.py")
SPEC = importlib.util.spec_from_file_location("run_migration_plan", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self, status="SUCCEEDED", result=None):
        self.status = status
        self.result = result or {"rejected": 0, "targetVerified": True}
        self.created = []

    def request(self, path, method="GET", payload=None):
        if path == "/api/v1/task-instances":
            self.created.append(payload)
            return {"id": f"task-{len(self.created)}"}
        if path.endswith("/results"):
            return {"status": self.status, "steps": [{"stepName": "migrate_users",
                                                        "status": self.status,
                                                        "result": self.result}]}
        return {"id": path.rsplit("/", 1)[-1], "status": self.status}


class MigrationPlanTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest = self.root / "backup.json"
        self.manifest.write_text('{"sealed":true}\n', encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def plan(self):
        return {"approved": True, "runId": "run_1",
                "backup": {"manifestPath": str(self.manifest),
                           "sha256": hashlib.sha256(self.manifest.read_bytes()).hexdigest()},
                "phases": [{"name": "identity", "tasks": [{
                    "taskName": "identity_migrate_users",
                    "parameters": {"migrationKey": "identity_1", "dryRun": True,
                                   "snapshotHighWater": 4},
                    "resultStep": "migrate_users",
                    "assertions": [{"path": "rejected", "equals": 0},
                                   {"path": "targetVerified", "equals": True}]}]}]}

    def test_runs_serial_tasks_and_returns_redacted_evidence(self):
        client = FakeClient()
        report = MODULE.run(self.plan(), client, 1)
        self.assertTrue(report["ready"])
        self.assertEqual("DATA_MIGRATION", client.created[0]["businessType"])
        task = report["phases"][0]["tasks"][0]
        self.assertEqual({"rejected": 0, "targetVerified": True}, task["assertions"])
        self.assertNotIn("parameters", task)

    def test_rejects_unapproved_plan_and_bad_backup_hash(self):
        plan = self.plan()
        plan["approved"] = False
        with self.assertRaisesRegex(ValueError, "approved"):
            MODULE.validate_plan(plan)
        plan = self.plan()
        plan["backup"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "does not match"):
            MODULE.validate_plan(plan)

    def test_stops_on_failed_task_or_assertion(self):
        with self.assertRaisesRegex(RuntimeError, "finished as FAILED"):
            MODULE.run(self.plan(), FakeClient(status="FAILED"), 1)
        with self.assertRaisesRegex(RuntimeError, "assertion rejected failed"):
            MODULE.run(self.plan(), FakeClient(result={"rejected": 1,
                                                       "targetVerified": True}), 1)

    def test_writes_private_atomic_evidence(self):
        target = self.root / "evidence" / "result.json"
        MODULE.write_evidence(target, {"ready": True})
        self.assertEqual({"ready": True}, json.loads(target.read_text()))
        self.assertEqual(0o600, target.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
