"""Tests for safe service schema initialization."""

from __future__ import annotations

import importlib.util
import os
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("initialize_schemas.py")
SPEC = importlib.util.spec_from_file_location("initialize_schemas", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
initializer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(initializer)


class InitializeSchemasTest(unittest.TestCase):
    """Verify manifest integrity and non-destructive SQL generation."""

    def setUp(self) -> None:
        self.manifest = initializer.load_manifest(Path(__file__).with_name("services.json"))

    def test_manifest_has_unique_ports_and_schemas(self) -> None:
        services = self.manifest["services"]
        self.assertEqual("/opt/yuyutian/mytools", self.manifest["deploymentRoot"])
        self.assertEqual(len(services), len({service["port"] for service in services}))
        self.assertEqual(len(services), len({service["schema"] for service in services}))

    def test_manifest_references_existing_service_directories(self) -> None:
        service_root = Path(__file__).resolve().parents[1]
        entries = self.manifest["services"] + self.manifest["statelessServices"]

        self.assertTrue(all((service_root / entry["name"]).is_dir() for entry in entries))

    def test_environment_template_keeps_risky_features_disabled(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            environment = initializer.parse_env_file(Path(__file__).with_name("env.example"))
        enabled_flags = {
            key: value
            for key, value in environment.items()
            if key.endswith("_ENABLED") or key.endswith("_MODE")
        }

        self.assertTrue(enabled_flags)
        self.assertTrue(all(value.lower() in {"false", "disabled"} for value in enabled_flags.values()))
        self.assertEqual("/opt/yuyutian/mytools", environment["MYTOOLS_SERVICE_ROOT"])
        self.assertEqual("/opt/yuyutian/logs/mytools", environment["MYTOOLS_LOG_ROOT"])
        deployment_paths = [
            value
            for key, value in environment.items()
            if key != "MYTOOLS_SERVICE_ROOT"
            and key.startswith("TASK_EXECUTOR_")
            and key.endswith("_ROOT")
        ]
        self.assertTrue(all(value.startswith("/opt/yuyutian/mytools/") for value in deployment_paths))
        self.assertFalse(environment["DOWNLOAD_DESTINATION_ROOT"].startswith("/opt/yuyutian/mytools/"))
        self.assertFalse(environment["STORAGE_DEFAULT_ROOT_PATH"].startswith("/opt/yuyutian/mytools/"))

    def test_statements_only_target_declared_schemas(self) -> None:
        environment: dict[str, str] = {}
        for service in self.manifest["services"]:
            prefix = service["dbPrefix"]
            environment[f"{prefix}_DB_USER"] = service["schema"]
            environment[f"{prefix}_DB_PASSWORD"] = "test-password"

        statements = initializer.sql_statements(self.manifest, environment)
        sql = "\n".join(statement for statement, _ in statements).upper()

        self.assertNotIn("DROP ", sql)
        self.assertNotIn("DELETE ", sql)
        self.assertNotIn("TRUNCATE ", sql)
        self.assertEqual(len(self.manifest["services"]), sql.count("CREATE DATABASE"))

    def test_missing_password_is_rejected(self) -> None:
        service = self.manifest["services"][0]
        environment = {f"{service['dbPrefix']}_DB_USER": service["schema"]}

        with self.assertRaisesRegex(ValueError, "PASSWORD"):
            initializer.sql_statements({"services": [service]}, environment)


if __name__ == "__main__":
    unittest.main()
