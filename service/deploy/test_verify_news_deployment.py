"""静态发布完整性与实际依赖检查边界的离线测试。"""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import verify_news_deployment as verify
from news_deployment_config import NewsConfigurationError
from test_news_deployment_config import shadow


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dependency_jar(name, version):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        if name.startswith("flyway-"):
            archive.writestr(f"META-INF/maven/org.flywaydb/{name}/pom.properties", f"version={version}\n")
        else:
            archive.writestr("META-INF/MANIFEST.MF", f"Implementation-Version: {version}\n")
    return stream.getvalue()


class VerifyNewsDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / "release";self.root.mkdir()
        (self.root / "apps").mkdir()
        entries = []
        for service, members in verify.JAR_MEMBERS.items():
            with zipfile.ZipFile(self.root / "apps" / (service + ".jar"), "w") as archive:
                for name in members:
                    archive.writestr("BOOT-INF/classes/" + name, "offline fixture")
                if service in verify.MIGRATION_RUNTIME_SERVICES:
                    for name, version in verify.MIGRATION_RUNTIME_LIBRARIES.items():
                        archive.writestr(f"BOOT-INF/lib/{name}-{version}.jar", dependency_jar(name, version))
            entries.append({"name": service, "port": 23350 if service == "news-digest-service" else 1,
                            "schema": "mytools_news" if service == "news-digest-service" else "other",
                            "defaultEnabled": False})
        (self.root / "services.json").write_text(json.dumps({"services": entries, "statelessServices": []}))
        package_entries = []
        for name, version in sorted(verify.PACKAGES):
            directory = self.root / "task-packages" / name / version
            (directory / "scripts").mkdir(parents=True)
            (directory / "scripts/main.py").write_text("# Offline fixture\n")
            if name == "news_resolve_region":
                (directory / "requirements.txt").write_text("maxminddb==3.2.0\n")
            files = [{"path": path.relative_to(directory).as_posix(), "sizeBytes": path.stat().st_size, "sha256": file_hash(path)}
                     for path in sorted(directory.rglob("*")) if path.is_file()]
            package_entries.append({"name": name, "version": version, "entrypoint": "scripts/main.py", "files": files})
        (self.root / "task-packages/package-index.json").write_text(json.dumps({"packages": package_entries, "packageCount": len(package_entries),
            "contentSha256": hashlib.sha256(json.dumps(package_entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()}))
        self.inventory()

    def inventory(self):
        files = [{"path": path.relative_to(self.root).as_posix(), "size": path.stat().st_size, "sha256": file_hash(path)}
                 for path in sorted(self.root.rglob("*")) if path.is_file() and path.name != "release-manifest.json"]
        (self.root / "release-manifest.json").write_text(json.dumps({"files": files, "releaseId": "offline-fixture"}))

    def test_exact_source_inventory_and_required_feature_members_are_checked(self):
        result = verify.inspect_release(self.root)
        self.assertEqual(3, result["packageCount"])
        self.assertFalse(result["runtimeAcceptance"])
        self.assertEqual("exact_source", result["inventoryMode"])
        self.assertTrue(result["pinnedMigrationRuntimeVerified"])
        (self.root / "unexpected-file").write_text("unexpected")
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root)

    def test_missing_migration_is_rejected_even_when_outer_digest_is_updated(self):
        path = self.root / "apps/news-digest-service.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("BOOT-INF/classes/other.class", "fixture")
        self.inventory()
        with self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_release(self.root)
        self.assertEqual("NEWS_RELEASE_INVALID", caught.exception.code)

    def test_duplicate_engine_versions_are_rejected(self):
        path = self.root / "apps/news-digest-service.jar"
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("BOOT-INF/lib/flyway-core-9.22.3.jar", "old engine")
        self.inventory()
        with self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_release(self.root)
        self.assertEqual("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID", caught.exception.code)

    def test_renamed_or_missing_library_cannot_satisfy_pinned_runtime(self):
        path = self.root / "apps/messaging-service.jar"
        with zipfile.ZipFile(path) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        contents["BOOT-INF/lib/flyway-core-11.20.3.jar"] = dependency_jar("flyway-core", "9.22.3")
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in contents.items():
                archive.writestr(name, content)
        self.inventory()
        with self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_release(self.root)
        self.assertEqual("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID", caught.exception.code)
        with zipfile.ZipFile(path) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()
                        if name != "BOOT-INF/lib/flyway-core-11.20.3.jar"}
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in contents.items():
                archive.writestr(name, content)
        self.inventory()
        with self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_release(self.root)
        self.assertEqual("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID", caught.exception.code)

    def test_package_tampering_and_unindexed_script_are_rejected(self):
        path = self.root / "task-packages/news_generate_edition/1.0.0/scripts/main.py"
        path.write_text("changed");self.inventory()
        with self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_release(self.root)
        self.assertEqual("NEWS_RELEASE_PACKAGE_INVALID", caught.exception.code)
        path.write_text("# Offline fixture\n")
        (path.parent / "unindexed.py").write_text("unindexed");self.inventory()
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root)

    def test_index_path_traversal_and_aggregate_hash_mismatch_are_rejected(self):
        index_path = self.root / "task-packages/package-index.json"
        index = json.loads(index_path.read_text())
        index["packages"][0]["files"][0]["path"] = "../../outside"
        index["contentSha256"] = hashlib.sha256(json.dumps(index["packages"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        index_path.write_text(json.dumps(index));self.inventory()
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root)
        index["contentSha256"] = "a" * 64;index_path.write_text(json.dumps(index));self.inventory()
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root)

    def test_installed_payload_allows_generated_venv_but_not_changed_apps(self):
        venv = self.root / "venv/bin";venv.mkdir(parents=True)
        (venv / "python3").write_text("installed runtime")
        self.assertEqual("installed_payload", verify.inspect_release(self.root, installed=True)["inventoryMode"])
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root)
        (self.root / "apps/unindexed.jar").write_text("unindexed")
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root, installed=True)

    def test_changed_original_payload_is_rejected_after_install(self):
        (self.root / "services.json").write_text("{}");
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_release(self.root, installed=True)

    def test_packaged_cli_upgrade_and_preflight_do_not_mutate_release_or_print_secrets(self):
        deploy = self.root / "deploy";deploy.mkdir()
        for name in ("verify_news_deployment.py", "prepare_news_environment.py", "news_deployment_config.py", "install_release.py"):
            shutil.copyfile(Path(__file__).with_name(name), deploy / name)
        self.inventory()
        config = self.root.parent / "private.env"
        original = b"SMTP_PASSWORD=fixture-existing-secret\n"
        config.write_bytes(original);config.chmod(0o600)
        def execute(script, *arguments):
            result = subprocess.run([sys.executable, str(deploy / script), "--env-file", str(config), *arguments],
                                    env={"PATH": "/usr/bin:/bin"}, capture_output=True, text=True, timeout=10, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn("fixture-existing-secret", result.stdout + result.stderr)
            return json.loads(result.stdout)
        preview = execute("prepare_news_environment.py")
        self.assertFalse(preview["changed"]);self.assertEqual(original, config.read_bytes())
        self.assertTrue(execute("prepare_news_environment.py", "--apply")["changed"])
        saved = config.read_bytes()
        self.assertFalse(execute("prepare_news_environment.py", "--apply")["changed"])
        self.assertEqual(saved, config.read_bytes())
        result = execute("verify_news_deployment.py", "--release", str(self.root))
        self.assertTrue(result["configurationValid"]);self.assertTrue(result["release"]["releaseFilesValid"])
        self.assertFalse(result["liveAcceptance"])
        self.assertFalse(list(self.root.rglob("__pycache__")))
        verify.inspect_release(self.root)

    def runtime_values(self):
        python = self.root / "venv/bin/python3";python.parent.mkdir(parents=True);python.write_text("fixture");python.chmod(0o700)
        work = self.root.parent / "work";work.mkdir()
        city = self.root.parent / "city.mmdb";city.write_text("fixture")
        return shadow() | {"TASK_EXECUTOR_SCRIPT_ROOT": str(self.root / "task-packages"), "TASK_EXECUTOR_PYTHON_EXECUTABLE": str(python),
                           "TASK_EXECUTOR_WORK_ROOT": str(work), "NEWS_REGION_MMDB_PATH": str(city)}

    @patch.object(verify.os, "geteuid", return_value=123)
    @patch.object(verify.subprocess, "run", return_value=SimpleNamespace(returncode=0))
    def test_runtime_uses_release_python_and_passes_no_environment_secrets(self, run, _uid):
        values = self.runtime_values()
        result = verify.inspect_runtime(values, self.root)
        self.assertFalse(result["networkProbed"])
        self.assertEqual({"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}, run.call_args.kwargs["env"])
        self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stderr"])
        self.assertNotIn(values["NEWS_ADMIN_TOKEN"], repr(run.call_args))
        values["TASK_EXECUTOR_PYTHON_EXECUTABLE"] = "/usr/bin/python3"
        with self.assertRaises(NewsConfigurationError):
            verify.inspect_runtime(values, self.root)
        self.assertEqual(1, run.call_count)

    @patch.object(verify.os, "geteuid", return_value=123)
    @patch.object(verify.subprocess, "run", return_value=SimpleNamespace(returncode=2))
    def test_runtime_failure_is_sanitized_and_root_is_not_accepted(self, _run, _uid):
        values = self.runtime_values()
        with self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_runtime(values, self.root)
        self.assertEqual("NEWS_RUNTIME_DEPENDENCY_INVALID", caught.exception.code)
        with patch.object(verify.os, "geteuid", return_value=0), self.assertRaises(NewsConfigurationError) as caught:
            verify.inspect_runtime(values, self.root)
        self.assertEqual("NEWS_RUNTIME_NON_ROOT_REQUIRED", caught.exception.code)
