import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("create_service_env.py")
SPEC = importlib.util.spec_from_file_location("create_service_env", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CreateServiceEnvTest(unittest.TestCase):
    def manifest(self):
        return {"deploymentRoot": "/opt/yuyutian/mytools", "services": [
            {"name": "task-scheduler-service", "schema": "mytools_task", "dbPrefix": "TASK"},
            {"name": "reader-service", "schema": "mytools_reader", "dbPrefix": "READER"},
        ]}

    def test_generates_unique_secrets_and_independent_business_paths(self):
        first = MODULE.values(self.manifest(), "/data/downloads", "/srv/storage",
                              ["/media/library"], "managed")
        second = MODULE.values(self.manifest(), "/data/downloads", "/srv/storage", [], "managed")
        self.assertNotEqual(first["TASK_DB_PASSWORD"], second["TASK_DB_PASSWORD"])
        self.assertNotEqual(first["IDENTITY_JWT_SECRET"], second["IDENTITY_JWT_SECRET"])
        self.assertEqual("false", first["GATEWAY_READER_ROUTE_ENABLED"])
        self.assertEqual("http://127.0.0.1:23410", first["TASK_SCHEDULER_URL"])
        self.assertEqual('["/media/library"]', first["MEDIA_SCAN_ALLOWED_ROOTS"])
        self.assertEqual(first["RCLONE_RC_USER"], first["STORAGE_RCLONE_RC_USER"])
        self.assertEqual(first["RCLONE_RC_PASSWORD"], first["STORAGE_RCLONE_RC_PASSWORD"])
        self.assertEqual(first["LEGACY_ASSET_ADAPTER_TOKEN"],
                         first["LEGACY_ASSET_ADAPTER_INTERNAL_TOKEN"])
        self.assertEqual(first["MSGSERVICE_MIGRATION_TOKEN"],
                         first["MSGSERVICE_ADAPTER_INTERNAL_TOKEN"])
        self.assertEqual("http://127.0.0.1:23321", first["MSGSERVICE_MIGRATION_URL"])
        self.assertFalse(first["DOWNLOAD_DESTINATION_ROOT"].startswith("/opt/yuyutian/mytools"))

    def test_rejects_business_paths_under_deployment_or_logs(self):
        for path in ("relative", "/opt/yuyutian/mytools/downloads",
                     "/opt/yuyutian/logs/mytools/media"):
            with self.assertRaises(ValueError):
                MODULE.validate_business_path(path, "test root")

    def test_private_writer_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "services.env"
            with patch.object(MODULE, "OUTPUT", target):
                MODULE.write_private(target, b"KEY=value\n")
                self.assertEqual(0o600, target.stat().st_mode & 0o777)
                with self.assertRaisesRegex(ValueError, "already exists"):
                    MODULE.write_private(target, b"KEY=changed\n")

    def test_encoded_environment_is_sorted_and_parseable(self):
        content = MODULE.encode({"B_KEY": "two", "A_KEY": "one"})
        self.assertEqual(b"A_KEY=one\nB_KEY=two\n", content)
        self.assertEqual({"A_KEY": "one", "B_KEY": "two"},
                         dict(line.split("=", 1) for line in content.decode().splitlines()))


if __name__ == "__main__":
    unittest.main()
