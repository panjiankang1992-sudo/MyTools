import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("assemble_release.py")
SPEC = importlib.util.spec_from_file_location("assemble_release", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AssembleReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        deploy = self.root / "service" / "deploy"
        deploy.mkdir(parents=True)
        manifest = {"deploymentRoot": "/opt/yuyutian/mytools",
                    "services": [{"name": "api", "runtime": "java"},
                                 {"name": "worker", "runtime": "python"}],
                    "statelessServices": [{"name": "gateway", "runtime": "java"}]}
        (deploy / "services.json").write_text(json.dumps(manifest), encoding="utf-8")
        (deploy / "verify.py").write_text("pass\n", encoding="utf-8")
        (deploy / "verify_task_runtime_config.py").write_text("pass\n", encoding="utf-8")
        scripts = self.root / "service" / "scripts"
        scripts.mkdir()
        (scripts / "cutover_preflight.py").write_text("pass\n", encoding="utf-8")
        analysis = self.root / "service" / "reader-service" / "evaluation" / "audiobook_analysis"
        analysis.mkdir(parents=True)
        for name in ("evaluate.py", "issue_quality_gate_attestation.py", "README.md"):
            (analysis / name).write_text("pass\n", encoding="utf-8")
        monitoring = deploy / "monitoring"
        monitoring.mkdir()
        (monitoring / "task-reliability-alerts.yml").write_text("groups: []\n", encoding="utf-8")
        (monitoring / "task-reliability-dashboard.json").write_text("{}\n", encoding="utf-8")
        (monitoring / "audiobook-generation-alerts.yml").write_text("groups: []\n", encoding="utf-8")
        (monitoring / "audiobook-generation-dashboard.json").write_text("{}\n", encoding="utf-8")
        runbooks = self.root / "docs" / "runbooks"
        runbooks.mkdir(parents=True)
        (runbooks / "task-reliability-alerts.md").write_text("# Runbook\n", encoding="utf-8")
        (runbooks / "audiobook-generation-alerts.md").write_text("# Runbook\n", encoding="utf-8")
        for name in ("api", "gateway"):
            target = self.root / "service" / name / "target"
            target.mkdir(parents=True)
            (target / f"{name}-1.jar").write_bytes(name.encode())
        worker = self.root / "service" / "worker"
        (worker / "src" / "worker").mkdir(parents=True)
        (worker / "src" / "worker" / "__init__.py").write_text("", encoding="utf-8")
        (worker / "pyproject.toml").write_text("[project]\nname='worker'\nversion='1'\n",
                                                encoding="utf-8")
        migrations = worker / "db" / "migrations"
        migrations.mkdir(parents=True)
        (migrations / "V1__create_worker.sql").write_text("CREATE TABLE worker(id INT);\n")
        sdk = self.root / "service" / "task-executor-service" / "sdk" / "python"
        sdk.mkdir(parents=True)
        (sdk / "README.md").write_text("sdk")

    def tearDown(self):
        self.temporary.cleanup()

    @patch.object(MODULE, "assemble_task_packages")
    def test_assembles_complete_immutable_inventory(self, packages):
        packages.side_effect = lambda _repository, target: (target.mkdir(),
                                                              (target / "index.json").write_text("{}"))
        output = self.root / "release"
        report = MODULE.assemble(self.root, output, "release_1", skip_java_build=True)
        self.assertEqual(2, report["javaServiceCount"])
        self.assertEqual(1, report["pythonServiceCount"])
        self.assertTrue((output / "apps" / "api.jar").is_file())
        self.assertTrue((output / "python-src" / "worker" / "pyproject.toml").is_file())
        self.assertTrue((output / "python-src" / "worker" / "db" / "migrations"
                         / "V1__create_worker.sql").is_file())
        self.assertTrue((output / "deploy" / "monitoring" / "task-reliability-alerts.yml").is_file())
        self.assertTrue((output / "deploy" / "monitoring" / "task-reliability-dashboard.json").is_file())
        self.assertTrue((output / "deploy" / "monitoring" / "task-reliability-alerts.md").is_file())
        self.assertTrue((output / "deploy" / "monitoring" / "audiobook-generation-alerts.yml").is_file())
        self.assertTrue((output / "deploy" / "monitoring" / "audiobook-generation-dashboard.json").is_file())
        self.assertTrue((output / "deploy" / "monitoring" / "audiobook-generation-alerts.md").is_file())
        self.assertTrue((output / "deploy" / "verify_task_runtime_config.py").is_file())
        self.assertTrue((output / "operator-tools" / "cutover_preflight.py").is_file())
        self.assertTrue((output / "operator-tools" / "audiobook-analysis" / "evaluate.py").is_file())
        self.assertTrue((output / "operator-tools" / "audiobook-analysis"
                         / "issue_quality_gate_attestation.py").is_file())
        stored = json.loads((output / "release-manifest.json").read_text())
        self.assertEqual(report, stored)
        self.assertNotIn("release-manifest.json", {item["path"] for item in report["files"]})

    def test_rejects_ambiguous_jar_and_existing_output(self):
        (self.root / "service" / "api" / "target" / "api-2.jar").write_bytes(b"other")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            MODULE.resolve_jar(self.root / "service", "api")
        output = self.root / "existing"
        output.mkdir()
        with self.assertRaisesRegex(ValueError, "already exists"):
            MODULE.assemble(self.root, output, "release_1", skip_java_build=True)

    def test_removes_transient_python_artifacts_before_inventory(self):
        cache = self.root / "release" / "task-executor-sdk" / "package" / "__pycache__"
        cache.mkdir(parents=True)
        compiled = cache / "module.cpython-314.pyc"
        compiled.write_bytes(b"compiled")
        stray = self.root / "release" / "stray.pyc"
        stray.write_bytes(b"compiled")

        MODULE.remove_transient_python_artifacts(self.root / "release")

        self.assertFalse(cache.exists())
        self.assertFalse(stray.exists())


if __name__ == "__main__":
    unittest.main()
