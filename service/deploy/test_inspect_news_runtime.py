"""运行环境检查的脱敏、进程竞争与最终配置边界验证。"""
import base64
import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import inspect_news_runtime as runtime
import generate_systemd_units as systemd


class InspectNewsRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.secret = "private-value-must-not-appear-" + "x" * 32

    def item(self, values=None, arguments=()):
        return runtime.Snapshot(100, "123", values or {}, arguments, True)

    def invoke(self, snapshots):
        pids = {runtime.UNITS[role]: 100 + i for i, role in enumerate(snapshots)}
        by_pid = {pids[runtime.UNITS[role]]: item for role, item in snapshots.items()}
        with patch.object(runtime, "unit_state", side_effect=lambda unit: ("RUNNING", pids[unit]) if unit in pids else ("NOT_INSTALLED", 0)), \
                patch.object(runtime, "snapshot", side_effect=lambda pid: by_pid[pid]), \
                patch.object(runtime, "start_stamp", return_value="123"):
            return runtime.inspect()

    def test_missing_news_process_and_opaque_credentials_never_claim_acceptance(self):
        item = self.item({"NEWS_JOB_TOKEN": self.secret, "UNRELATED_SECRET": self.secret})
        result = self.invoke({"executor": item})
        self.assertEqual("NOT_INSTALLED", result["roles"]["news"]["state"])
        self.assertEqual("PROCESS_UNAVAILABLE", result["environmentCredentialBindings"]["news_executor"])
        self.assertEqual("MISSING", result["environmentNodeBinding"])
        self.assertFalse(result["springBindingVerified"])
        self.assertFalse(result["liveAcceptance"])
        self.assertNotIn(self.secret, json.dumps(result))
        self.assertNotIn("UNRELATED_SECRET", json.dumps(result))
        self.assertEqual("Snapshot[redacted]", repr(item))

    def test_cross_role_mismatch_and_empty_values_are_distinguished(self):
        news = self.item({"NEWS_INTERNAL_TOKEN": self.secret, "NEWS_JOB_TOKEN": self.secret,
                          "TASK_BUSINESS_NEWS_TOKEN": self.secret, "NEWS_REGION_NODE_ID": "fixture-node"})
        gateway = self.item({"NEWS_INTERNAL_TOKEN": self.secret + "different"})
        executor = self.item({"NEWS_JOB_TOKEN": self.secret, "TASK_NEWS_NODE_VALUE": "fixture-node",
                              "TASK_EXECUTOR_NODE_NAME": "fixture-node"})
        scheduler = self.item({"TASK_NEWS_NODE_VALUE": "fixture-node"})
        result = self.invoke({"news": news, "gateway": gateway, "executor": executor, "scheduler": scheduler})
        self.assertEqual("MISMATCH", result["environmentCredentialBindings"]["news_gateway"])
        self.assertEqual("MATCH", result["environmentCredentialBindings"]["news_executor"])
        self.assertEqual("MISSING", result["environmentCredentialBindings"]["news_scheduler"])
        self.assertEqual("MATCH", result["environmentNodeBinding"])
        self.assertNotIn("fixture-node", json.dumps(result))

    def test_weak_credentials_and_invalid_encryption_key_only_return_known_names(self):
        item = self.item({"MESSAGING_OUTBOUND_ADMIN_TOKEN": "too-short",
                          "MESSAGING_DESTINATION_ENCRYPTION_KEY": self.secret})
        result = runtime.inspect_role("messaging", item)
        self.assertEqual(["MESSAGING_OUTBOUND_ADMIN_TOKEN", "MESSAGING_DESTINATION_ENCRYPTION_KEY"], result["invalidEnvironmentKeys"])
        item.values["MESSAGING_DESTINATION_ENCRYPTION_KEY"] = base64.b64encode(b"x" * 32).decode()
        self.assertEqual(["MESSAGING_OUTBOUND_ADMIN_TOKEN"], runtime.inspect_role("messaging", item)["invalidEnvironmentKeys"])

    def test_high_priority_configuration_is_detected_without_printing_values(self):
        item = self.item({"SPRING_APPLICATION_JSON": self.secret, "SPRING_CONFIG_IMPORT": self.secret,
                          "JAVA_TOOL_OPTIONS": self.secret, "SPRING_PROFILES_ACTIVE": self.secret},
                         ("java", "--news.internal-token=" + self.secret, "-Dexecutor.node-name=" + self.secret))
        signals = runtime.override_signals(item)
        self.assertEqual(["ACTIVE_SPRING_PROFILES", "COMMAND_LINE_PROPERTIES", "EXTERNAL_SPRING_CONFIG",
                          "JVM_OPTIONS_ENVIRONMENT", "JVM_SYSTEM_PROPERTIES", "SPRING_JSON"], signals)
        self.assertNotIn(self.secret, json.dumps(runtime.inspect_role("news", item)))

    def test_package_paths_are_candidates_and_never_execute_environment_commands(self):
        root = self.root / "packages"
        file = root / "news_resolve_region/1.2.0/scripts/main.py"
        file.parent.mkdir(parents=True);file.write_text("raise RuntimeError('must not execute')")
        (root / "package-index.json").write_text("not a valid index")
        item = self.item({"TASK_EXECUTOR_SCRIPT_ROOT": str(root), "TASK_EXECUTOR_PYTHON_EXECUTABLE": str(file)})
        with patch.object(runtime.subprocess, "run") as run:
            result = runtime.inspect_role("executor", item)["executorCandidates"]
        run.assert_not_called()
        self.assertTrue(result["packageFilesPresent"]["news_resolve_region"])
        self.assertFalse(result["packageFilesPresent"]["news_generate_edition"])
        self.assertTrue(result["packageIndexPresent"])
        self.assertFalse(result["packageIntegrityVerified"])
        self.assertFalse(result["servicePermissionsVerified"])
        self.assertNotIn(str(root), json.dumps(result))
        self.assertFalse(runtime.candidate_file("relative/path"))
        self.assertFalse(runtime.candidate_file("/invalid\0path"))

    def test_geolocation_provider_uses_actual_configuration_names(self):
        item = self.item({"NEWS_REGION_PROVIDER": "MAXMIND_WEB", "NEWS_REGION_MAXMIND_ACCOUNT": "12345",
                          "NEWS_REGION_MAXMIND_LICENSE": self.secret})
        self.assertTrue(runtime.inspect_role("executor", item)["executorCandidates"]["geolocationPrerequisitePresent"])
        item.values["NEWS_REGION_MAXMIND_HOST"] = "unapproved.invalid"
        self.assertFalse(runtime.inspect_role("executor", item)["executorCandidates"]["geolocationPrerequisitePresent"])
        file = self.root / "candidate.mmdb";file.write_text("not validated")
        item.values.update(NEWS_REGION_PROVIDER="MAXMIND_MMDB", NEWS_REGION_MMDB_PATH=str(file))
        self.assertTrue(runtime.inspect_role("executor", item)["executorCandidates"]["geolocationPrerequisitePresent"])
        item.values.update(NEWS_REGION_PROVIDER="IPWHOIS", NEWS_REGION_MMDB_PATH="", NEWS_REGION_MAXMIND_ACCOUNT="", NEWS_REGION_MAXMIND_LICENSE="")
        self.assertTrue(runtime.inspect_role("executor", item)["executorCandidates"]["geolocationPrerequisitePresent"])

    def proc(self, environment):
        path = self.root / "100";path.mkdir()
        (path / "stat").write_text("100 (java (name)) " + " ".join(["S"] + ["0"] * 18 + ["123"]))
        (path / "environ").write_bytes(environment)
        (path / "cmdline").write_bytes(b"java\0-Dnews.internal-token=hidden\0")
        (path / "status").write_text("Uid:\t1000\t1000\t1000\t1000\n")
        return path

    def test_proc_snapshot_parses_start_identity_and_non_root_without_secret_repr(self):
        self.proc(("NEWS_JOB_TOKEN=" + self.secret + "\0").encode())
        result = runtime.snapshot(100, self.root)
        self.assertEqual("123", result.start)
        self.assertTrue(result.non_root)
        self.assertEqual(self.secret, result.values["NEWS_JOB_TOKEN"])
        self.assertNotIn(self.secret, repr(result))

    def test_duplicate_environment_and_partial_proc_reads_fail_closed(self):
        path = self.proc(b"TOKEN=first\0TOKEN=second\0")
        with self.assertRaises(runtime.InspectionError):
            runtime.snapshot(100, self.root)
        (path / "environ").write_bytes(b"KEY=\xff\0")
        with self.assertRaises(runtime.InspectionError):
            runtime.snapshot(100, self.root)
        (path / "environ").write_bytes(b"KEY=value\0")
        with patch.object(runtime, "start_stamp", side_effect=["123", "124"]):
            with self.assertRaises(runtime.InspectionError):
                runtime.snapshot(100, self.root)

    def test_any_root_identity_is_reported(self):
        path = self.proc(b"KEY=value\0")
        (path / "status").write_text("Uid:\t1000\t0\t1000\t1000\n")
        self.assertFalse(runtime.snapshot(100, self.root).non_root)

    def test_cross_service_restart_discards_all_snapshots(self):
        with patch.object(runtime, "unit_state", side_effect=[("NOT_INSTALLED", 0)] * 5 + [("RUNNING", 200)]):
            with self.assertRaisesRegex(runtime.InspectionError, "NEWS_RUNTIME_CHANGED_DURING_INSPECTION"):
                runtime.inspect()

    def test_pid_reuse_discards_cross_service_report(self):
        with patch.object(runtime, "unit_state", return_value=("RUNNING", 100)), \
                patch.object(runtime, "snapshot", return_value=self.item()), \
                patch.object(runtime, "start_stamp", return_value="different"):
            with self.assertRaisesRegex(runtime.InspectionError, "NEWS_RUNTIME_CHANGED_DURING_INSPECTION"):
                runtime.inspect()

    def test_systemctl_reads_only_allowed_status_fields_with_clean_child_environment(self):
        response = SimpleNamespace(returncode=0, stdout=b"LoadState=loaded\nActiveState=active\nMainPID=100\n")
        with patch.object(runtime.subprocess, "run", return_value=response) as run:
            self.assertEqual(("RUNNING", 100), runtime.unit_state(runtime.UNITS["news"]))
        self.assertNotIn("Environment", " ".join(run.call_args.args[0]))
        self.assertEqual(runtime.CHILD_ENV, run.call_args.kwargs["env"])
        self.assertEqual(5, run.call_args.kwargs["timeout"])
        response.stdout = b"LoadState=not-found\nActiveState=inactive\nMainPID=0\n"
        with patch.object(runtime.subprocess, "run", return_value=response):
            self.assertEqual(("NOT_INSTALLED", 0), runtime.unit_state(runtime.UNITS["news"]))

    def test_cli_error_redacts_system_exception_and_returns_no_partial_result(self):
        output = io.StringIO()
        with patch.object(runtime, "inspect", side_effect=OSError(self.secret)), contextlib.redirect_stdout(output):
            self.assertEqual(2, runtime.main([]))
        result = json.loads(output.getvalue())
        self.assertFalse(result["inspectionCompleted"])
        self.assertEqual("NEWS_RUNTIME_INSPECTION_FAILED", result["code"])
        self.assertNotIn(self.secret, output.getvalue())
        self.assertNotIn("roles", result)

    def test_oversized_process_data_is_rejected(self):
        file = self.root / "oversized";file.write_bytes(b"x" * (runtime.MAX_BYTES + 1))
        with self.assertRaises(ValueError):
            runtime.read_bounded(file)

    def test_unit_names_match_actual_deployment_generator(self):
        manifest = systemd.load_manifest(Path(__file__).with_name("services.json"))
        output = self.root / "units"
        written = {path.name for path in systemd.generate(manifest, output)}
        self.assertTrue(set(runtime.UNITS.values()).issubset(written))


if __name__ == "__main__":
    unittest.main()
