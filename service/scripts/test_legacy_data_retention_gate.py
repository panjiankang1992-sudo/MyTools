import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("legacy_data_retention_gate.py")
SPEC = importlib.util.spec_from_file_location("legacy_data_retention_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def valid_manifest():
    return {
        "backupFile": "/backup/mytools.sql.gz",
        "sha256": "a" * 64,
        "readVerified": True,
        "inventoryComplete": True,
        "unclassifiedTables": [],
        "tables": {name: 0 for name in MODULE.REQUIRED_TABLES},
    }


class LegacyDataRetentionGateTest(unittest.TestCase):
    def test_accepts_complete_verified_backup(self):
        manifest = valid_manifest()
        manifest["tables"]["local_file"] = 12
        report = MODULE.evaluate(manifest)
        self.assertTrue(report["ready"])
        self.assertEqual(12, report["totalRows"])

    def test_rejects_missing_table_and_unverified_backup(self):
        manifest = valid_manifest()
        del manifest["tables"]["t_feedback"]
        manifest["readVerified"] = False
        report = MODULE.evaluate(manifest)
        self.assertFalse(report["ready"])
        self.assertEqual(2, len(report["errors"]))

    def test_rejects_unknown_inventory_and_invalid_count(self):
        manifest = valid_manifest()
        manifest["unclassifiedTables"] = ["custom_table"]
        manifest["tables"]["local_file"] = -1
        report = MODULE.evaluate(manifest)
        self.assertFalse(report["ready"])
        self.assertEqual(2, len(report["errors"]))


if __name__ == "__main__":
    unittest.main()
