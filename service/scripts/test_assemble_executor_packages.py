import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("assemble_executor_packages.py")
SPEC = importlib.util.spec_from_file_location("assemble_executor_packages", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def create_package(root: Path, name: str = "example_task", version: str = "1.0.0") -> Path:
    package = root / "domain/packages" / name / version
    (package / "scripts").mkdir(parents=True)
    (package / "scripts/main.py").write_text("print('ok')\n", encoding="utf-8")
    (package / "tests").mkdir()
    (package / "tests/test_main.py").write_text("def test_ok(): pass\n", encoding="utf-8")
    (package / "manifest.yaml").write_text(
        f"name: {name}\nversion: {version}\nruntime: python3.12\nentrypoint: scripts/main.py\n",
        encoding="utf-8")
    return package


class AssembleExecutorPackagesTest(unittest.TestCase):

    def test_assembles_flat_immutable_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "service"
            create_package(root)
            output = Path(directory) / "release"
            index = MODULE.assemble(root, output)
            self.assertEqual(1, index["packageCount"])
            self.assertTrue((output / "example_task/1.0.0/scripts/main.py").is_file())
            self.assertFalse((output / "example_task/1.0.0/tests").exists())
            stored = json.loads((output / "package-index.json").read_text(encoding="utf-8"))
            self.assertEqual(index["contentSha256"], stored["contentSha256"])
            with self.assertRaisesRegex(ValueError, "already exists"):
                MODULE.assemble(root, output)

    def test_rejects_manifest_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "service"
            package = create_package(root)
            (package / "manifest.yaml").write_text(
                "name: another_task\nversion: 1.0.0\nentrypoint: scripts/main.py\n",
                encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "name does not match"):
                MODULE.discover(root)

    def test_rejects_unsafe_entrypoint_and_symbolic_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "service"
            package = create_package(root)
            (package / "manifest.yaml").write_text(
                "name: example_task\nversion: 1.0.0\nentrypoint: ../main.py\n",
                encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "entrypoint is unsafe"):
                MODULE.discover(root)
            (package / "manifest.yaml").write_text(
                "name: example_task\nversion: 1.0.0\nentrypoint: scripts/main.py\n",
                encoding="utf-8")
            (package / "linked.py").symlink_to(package / "scripts/main.py")
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                MODULE.discover(root)


if __name__ == "__main__":
    unittest.main()
