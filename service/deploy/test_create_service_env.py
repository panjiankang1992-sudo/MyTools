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
        self.assertEqual("true", first["TASK_EXECUTOR_REQUIRE_NON_ROOT"])
        self.assertEqual("12", first["TASK_EXECUTOR_MAX_CONCURRENT_TASKS"])
        self.assertEqual("1073741824", first["TASK_EXECUTOR_DISK_MINIMUM_USABLE_BYTES"])
        self.assertEqual("5", first["TASK_EXECUTOR_DISK_MINIMUM_USABLE_PERCENT"])
        self.assertEqual("21600", first["TASK_EXECUTOR_MAXIMUM_CPU_SECONDS"])
        self.assertEqual("17179869184", first["TASK_EXECUTOR_MAXIMUM_VIRTUAL_MEMORY_BYTES"])
        self.assertEqual("107374182400", first["TASK_EXECUTOR_MAXIMUM_FILE_BYTES"])
        self.assertEqual("/usr/bin/ffmpeg", first["FFMPEG_BINARY"])
        service_tokens = [
            first["TASK_EXECUTOR_INTERNAL_TOKEN"],
            first["TASK_OPERATOR_INTERNAL_TOKEN"],
            first["TASK_BUSINESS_MYTOOLS_TOKEN"],
            first["TASK_BUSINESS_MESSAGING_TOKEN"],
            first["TASK_BUSINESS_DRIVE_TOKEN"],
            first["TASK_BUSINESS_MEDIA_LIBRARY_TOKEN"],
            first["TASK_BUSINESS_READER_TOKEN"],
            first["TASK_BUSINESS_STORAGE_GATEWAY_TOKEN"],
        ]
        self.assertEqual(len(service_tokens), len(set(service_tokens)))
        self.assertTrue(all(len(token) >= 32 for token in service_tokens))
        self.assertEqual("LEGACY", first["MESSAGING_REGISTRATION_MAIL_MODE"])
        self.assertEqual("0", first["MESSAGING_REGISTRATION_MAIL_CANARY_PERCENT"])
        self.assertEqual("false", first["MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED"])
        self.assertEqual(32, len(__import__("base64").b64decode(
            first["MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY"])))
        self.assertGreaterEqual(len(first["MESSAGING_REGISTRATION_MAIL_ROUTING_KEY"]), 32)
        self.assertGreaterEqual(len(first["MESSAGING_REGISTRATION_MAIL_SHADOW_HASH_KEY"]), 32)
        self.assertEqual('["/media/library"]', first["MEDIA_SCAN_ALLOWED_ROOTS"])
        self.assertEqual(first["RCLONE_RC_USER"], first["STORAGE_RCLONE_RC_USER"])
        self.assertEqual(first["RCLONE_RC_PASSWORD"], first["STORAGE_RCLONE_RC_PASSWORD"])
        self.assertEqual(first["LEGACY_ASSET_ADAPTER_TOKEN"],
                         first["LEGACY_ASSET_ADAPTER_INTERNAL_TOKEN"])
        self.assertEqual(first["MSGSERVICE_MIGRATION_TOKEN"],
                         first["MSGSERVICE_ADAPTER_INTERNAL_TOKEN"])
        self.assertEqual(first["ONEBOT_CONNECTOR_INTERNAL_TOKEN"],
                         first["MESSAGE_PROVIDER_RESOLVER_TOKEN"])
        self.assertEqual("http://127.0.0.1:23321", first["MSGSERVICE_MIGRATION_URL"])
        self.assertEqual("3", first["SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"])
        self.assertEqual("0", first["SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE"])
        self.assertEqual("false", first["DOWNLOADBOT_SNAPSHOT_EXPORT_ENABLED"])
        self.assertEqual("false", first["DOWNLOADBOT_RECONCILIATION_ENABLED"])
        self.assertEqual("true", first["MESSAGE_AUTOMATION_COMPLETION_RELAY_ENABLED"])
        self.assertEqual(first["ONEBOT_CONNECTOR_ACCOUNT_KEY"],
                         first["QQ_CONNECTOR_ONEBOT_ACCOUNT_KEY"])
        self.assertEqual(
            "/opt/yuyutian/mytools/runtime/qq/login-command-wal",
            first["QQ_CONNECTOR_LOGIN_WAL_PATH"])
        self.assertEqual(
            "/opt/yuyutian/mytools/runtime/qq/inbound-wal",
            first["QQ_CONNECTOR_INBOUND_WAL_PATH"])
        self.assertEqual("4", first["QQ_CONNECTOR_INBOUND_WORKER_CONCURRENCY"])
        self.assertEqual("1", first["QQ_CONNECTOR_INBOUND_WORKER_POLL_SECONDS"])
        self.assertEqual(
            "/opt/yuyutian/mytools/runtime/onebot/relogin.request",
            first["ONEBOT_CONNECTOR_RELOGIN_REQUEST_PATH"])
        self.assertEqual("/opt/napcat/cache/qrcode.png",
                         first["ONEBOT_CONNECTOR_QR_PATH"])
        self.assertEqual("250", first["MESSAGING_ONEBOT_ACCEPTANCE_RELAY_DELAY_MS"])
        self.assertEqual("250", first["MESSAGE_AUTOMATION_RELAY_DELAY_MS"])
        self.assertEqual("250", first["MESSAGE_AUTOMATION_COMPLETION_RELAY_DELAY_MS"])
        self.assertEqual("250", first["MESSAGE_AUTOMATION_RECONCILIATION_DELAY_MS"])
        self.assertEqual("250", first["MESSAGE_AUTOMATION_ACTION_STATUS_POLL_DELAY_MS"])
        self.assertEqual("250", first["TASK_EXECUTOR_POLL_MILLISECONDS"])
        self.assertEqual("0", first["TASK_EXECUTOR_RESERVED_CHILD_TASK_SLOTS"])
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
