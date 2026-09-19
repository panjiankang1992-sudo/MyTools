import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("install_release.py")
SPEC = importlib.util.spec_from_file_location("install_release", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class InstallReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        (self.source / "python-src" / "worker").mkdir(parents=True)
        (self.source / "python-src" / "worker" / "pyproject.toml").write_text("[project]\n")
        application = self.source / "apps" / "api.jar"
        application.parent.mkdir()
        application.write_bytes(b"jar")
        files = []
        for path in sorted(item for item in self.source.rglob("*") if item.is_file()):
            files.append({"path": path.relative_to(self.source).as_posix(),
                          "size": path.stat().st_size,
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        (self.source / "release-manifest.json").write_text(json.dumps({
            "releaseId": "release_1", "files": files}), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def test_validates_exact_inventory_and_rejects_tampering(self):
        self.assertEqual("release_1", MODULE.validate_release(self.source)["releaseId"])
        (self.source / "apps" / "api.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "validation failed"):
            MODULE.validate_release(self.source)

    def test_rejects_extra_file_and_symbolic_link(self):
        (self.source / "extra").write_text("unexpected")
        with self.assertRaisesRegex(ValueError, "validation failed"):
            MODULE.validate_release(self.source)
        (self.source / "extra").unlink()
        (self.source / "link").symlink_to("apps/api.jar")
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            MODULE.validate_release(self.source)

    @patch.object(MODULE, "install_python")
    def test_installs_and_switches_current_atomically(self, install_python):
        with patch.object(MODULE, "validate_destination",
                          side_effect=lambda root, release_id: root / "releases" / release_id), \
                patch.object(MODULE, "service_identity", return_value=(0, 0)), \
                patch.object(MODULE, "protect_release_tree"), patch.object(MODULE.os, "chown"):
            report = MODULE.install(self.source, self.root / "deployment", "python3", "mytools")
        self.assertTrue(report["ready"])
        current = self.root / "deployment" / "releases" / "current"
        self.assertEqual("release_1", current.readlink().as_posix())
        install_python.assert_called_once()

    def test_requires_exact_remote_deployment_root(self):
        with self.assertRaisesRegex(ValueError, "deployment root"):
            MODULE.validate_destination(Path("/tmp/mytools"), "release_1")

    def test_protects_release_and_configuration_parents(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(MODULE.os, "chown") as chown:
            root = Path(directory) / "deployment"

            MODULE.prepare_deployment_directories(root, 123, 456)

        ownership = {call.args[0]: call.args[1:3] for call in chown.call_args_list}
        self.assertEqual((0, 456), ownership[root])
        self.assertEqual((0, 456), ownership[root / "config"])
        self.assertEqual((0, 456), ownership[root / "releases"])
        self.assertEqual((123, 456), ownership[root / "runtime"])

    def test_rejects_service_user_with_a_different_primary_group(self):
        user = SimpleNamespace(pw_uid=123, pw_gid=456)
        group = SimpleNamespace(gr_gid=789)
        with patch.object(MODULE.pwd, "getpwnam", return_value=user), \
                patch.object(MODULE.grp, "getgrnam", return_value=group), \
                self.assertRaisesRegex(ValueError, "primary group"):
            MODULE.service_identity("mytools")

    def test_protects_installed_release_from_service_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(MODULE.os, "chown") as chown:
            release = Path(directory) / "release"
            script = release / "venv" / "bin" / "worker"
            data = release / "apps" / "service.jar"
            script.parent.mkdir(parents=True)
            data.parent.mkdir()
            script.write_text("#!/bin/sh\n", encoding="utf-8")
            script.chmod(0o755)
            data.write_bytes(b"jar")

            MODULE.protect_release_tree(release, 456)

            self.assertEqual(0o750, script.stat().st_mode & 0o777)
            self.assertEqual(0o640, data.stat().st_mode & 0o777)
            self.assertEqual(0o750, release.stat().st_mode & 0o777)
        self.assertTrue(all(call.args[1:3] == (0, 456) for call in chown.call_args_list))


if __name__ == "__main__":
    unittest.main()
