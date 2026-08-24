import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
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

    def test_accepts_explicitly_absent_known_table(self):
        manifest = valid_manifest()
        del manifest["tables"]["t_feedback"]
        manifest["absentTables"] = ["t_feedback"]
        report = MODULE.evaluate(manifest)
        self.assertTrue(report["ready"])
        self.assertEqual(1, report["absentTableCount"])

    def test_rejects_unknown_duplicate_and_overlapping_absent_tables(self):
        manifest = valid_manifest()
        manifest["absentTables"] = ["t_feedback", "t_feedback", "unknown_table"]
        report = MODULE.evaluate(manifest)
        self.assertFalse(report["ready"])
        self.assertEqual(3, len(report["errors"]))

    def test_rejects_unknown_inventory_and_invalid_count(self):
        manifest = valid_manifest()
        manifest["unclassifiedTables"] = ["custom_table"]
        manifest["tables"]["local_file"] = -1
        report = MODULE.evaluate(manifest)
        self.assertFalse(report["ready"])
        self.assertEqual(2, len(report["errors"]))

    def test_verifies_backup_file_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup = root / "mytools.sql.gz"
            backup.write_bytes(b"verified backup")
            manifest = valid_manifest()
            manifest["backupFile"] = backup.name
            manifest["sha256"] = hashlib.sha256(backup.read_bytes()).hexdigest()
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            report = MODULE.verify_backup(manifest, manifest_path)
            self.assertTrue(report["verified"])
            self.assertEqual(len(b"verified backup"), report["sizeBytes"])

    def test_rejects_missing_or_changed_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = valid_manifest()
            manifest["backupFile"] = "missing.sql.gz"
            missing = MODULE.verify_backup(manifest, root / "manifest.json")
            self.assertFalse(missing["verified"])
            self.assertIn("backupFile does not exist", missing["errors"])

            backup = root / "changed.sql.gz"
            backup.write_bytes(b"changed")
            manifest["backupFile"] = backup.name
            changed = MODULE.verify_backup(manifest, root / "manifest.json")
            self.assertFalse(changed["verified"])
            self.assertIn("backupFile SHA-256 does not match manifest", changed["errors"])

    def test_rejects_symbolic_link_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "backup.sql.gz"
            target.write_bytes(b"backup")
            link = root / "linked.sql.gz"
            link.symlink_to(target)
            manifest = valid_manifest()
            manifest["backupFile"] = link.name
            manifest["sha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
            report = MODULE.verify_backup(manifest, root / "manifest.json")
            self.assertFalse(report["verified"])
            self.assertIn("backupFile must not be a symbolic link", report["errors"])


if __name__ == "__main__":
    unittest.main()
