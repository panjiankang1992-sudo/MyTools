import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).with_name("migrate_user_resource_layout.py")
SPEC = importlib.util.spec_from_file_location("migrate_user_resource_layout", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ResourceMigrationTest(unittest.TestCase):
    def test_moves_exact_legacy_directories_and_creates_music(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in MODULE.DIRECTORIES:
                (root / name).mkdir()
                (root / name / f"{name}.bin").write_bytes(name.encode())
            report = MODULE.migrate(root, "yuyutian", True)
            self.assertTrue(report["applied"])
            for name in MODULE.DIRECTORIES:
                self.assertFalse((root / name).exists())
                self.assertTrue((root / "yuyutian" / name / f"{name}.bin").is_file())
            self.assertTrue((root / "yuyutian" / "music").is_dir())

    def test_rejects_ambiguous_existing_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "ebook").mkdir()
            (root / "yuyutian" / "ebook").mkdir(parents=True)
            with self.assertRaises(ValueError):
                MODULE.migrate(root, "yuyutian", False)

    def test_rechecks_an_already_migrated_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in MODULE.DIRECTORIES:
                target = root / "yuyutian" / name
                target.mkdir(parents=True)
                (target / f"{name}.bin").write_bytes(name.encode())
            report = MODULE.migrate(root, "yuyutian", True)
            self.assertTrue(all(item["alreadyMigrated"] for item in report["operations"]))
            self.assertTrue((root / "yuyutian" / "music").is_dir())


if __name__ == "__main__":
    unittest.main()
