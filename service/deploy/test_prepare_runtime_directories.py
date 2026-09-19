import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("prepare_runtime_directories.py")
SPEC = importlib.util.spec_from_file_location("prepare_runtime_directories", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PrepareRuntimeDirectoriesTest(unittest.TestCase):
    def manifest(self):
        return {"services": [{"name": "scheduler"}],
                "statelessServices": [{"name": "gateway"}]}

    def test_path_set_excludes_business_data(self):
        values = [str(path) for path in MODULE.paths(self.manifest())]
        self.assertIn("/opt/yuyutian/mytools/runtime/tasks", values)
        self.assertIn("/opt/yuyutian/mytools/runtime/qq", values)
        self.assertIn("/opt/yuyutian/mytools/runtime/onebot", values)
        self.assertIn("/opt/yuyutian/logs/mytools/scheduler", values)
        self.assertNotIn("/data/mytools/downloads", values)

    def test_prepares_exact_private_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ownership = {}

            def remember_owner(path, uid, gid, **_kwargs):
                ownership[Path(path)] = (uid, gid)

            with patch.object(MODULE, "DEPLOYMENT_ROOT", root / "deploy"), \
                    patch.object(MODULE, "LOG_ROOT", root / "logs"), \
                    patch.object(MODULE.os, "chown", side_effect=remember_owner):
                prepared = MODULE.prepare(self.manifest(), 1, 1)
                self.assertEqual(14, len(prepared))
                self.assertEqual(0o750, (root / "logs" / "gateway").stat().st_mode & 0o777)
                self.assertEqual((0, 1), ownership[root / "deploy"])
                self.assertEqual((0, 1), ownership[root / "deploy" / "config"])
                self.assertEqual((0, 1), ownership[root / "deploy" / "releases"])
                self.assertEqual((1, 1), ownership[root / "deploy" / "runtime"])

    def test_rejects_managed_symbolic_link(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.mkdir()
            deployment = root / "deploy"
            deployment.symlink_to(target, target_is_directory=True)
            with patch.object(MODULE, "DEPLOYMENT_ROOT", deployment), \
                    patch.object(MODULE, "LOG_ROOT", root / "logs"), \
                    self.assertRaisesRegex(ValueError, "symbolic link"):
                MODULE.prepare(self.manifest(), 1, 1)

    def test_rejects_service_user_with_a_different_primary_group(self):
        user = SimpleNamespace(pw_uid=123, pw_gid=456)
        group = SimpleNamespace(gr_gid=789)
        with patch.object(MODULE.pwd, "getpwnam", return_value=user), \
                patch.object(MODULE.grp, "getgrnam", return_value=group), \
                self.assertRaisesRegex(ValueError, "primary group"):
            MODULE.identity("mytools")


if __name__ == "__main__":
    unittest.main()
