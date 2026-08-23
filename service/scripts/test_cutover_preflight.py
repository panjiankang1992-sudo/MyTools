import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("cutover_preflight.py")
SPEC = importlib.util.spec_from_file_location("cutover_preflight", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CutoverPreflightTest(unittest.TestCase):
    def test_accepts_distinct_default_schemas_and_disabled_flags(self):
        report = MODULE.inspect({})
        self.assertTrue(report["ready"])
        self.assertEqual(0, report["greyRelease"]["readerTenantCount"])
        self.assertEqual(0, report["greyRelease"]["driveTenantCount"])
        self.assertEqual("LEGACY", report["greyRelease"]["identityValidationMode"])
        self.assertEqual([], report["errors"])

    def test_rejects_shared_schema_and_enabled_sidecar(self):
        report = MODULE.inspect({"READER_DB_NAME": "mytools_task",
                                 "READER_SEARCH_SIDECAR_ENABLED": "true"})
        self.assertFalse(report["ready"])
        self.assertEqual(2, len(report["errors"]))

    def test_reader_route_requires_explicit_tenant_allowlist(self):
        report = MODULE.inspect({"GATEWAY_READER_ROUTE_ENABLED": "true"}, allow_enabled=True)
        self.assertFalse(report["ready"])
        self.assertTrue(any("TENANT_ALLOWLIST is required" in error for error in report["errors"]))

    def test_reader_route_accepts_unique_positive_tenants(self):
        report = MODULE.inspect({"GATEWAY_READER_ROUTE_ENABLED": "true",
                                 "GATEWAY_READER_TENANT_ALLOWLIST": "55,56"}, allow_enabled=True)
        self.assertTrue(report["ready"])
        self.assertEqual(2, report["greyRelease"]["readerTenantCount"])

    def test_drive_route_requires_explicit_tenant_allowlist(self):
        report = MODULE.inspect({"GATEWAY_DRIVE_ROUTE_ENABLED": "true"}, allow_enabled=True)
        self.assertFalse(report["ready"])
        self.assertTrue(any("DRIVE_TENANT_ALLOWLIST is required" in error
                            for error in report["errors"]))

    def test_identity_route_requires_new_token_validation_mode(self):
        invalid = MODULE.inspect({"GATEWAY_IDENTITY_ROUTE_ENABLED": "true"}, allow_enabled=True)
        valid = MODULE.inspect({"GATEWAY_IDENTITY_ROUTE_ENABLED": "true",
                                "IDENTITY_VALIDATION_MODE": "DUAL"}, allow_enabled=True)
        self.assertFalse(invalid["ready"])
        self.assertTrue(valid["ready"])


if __name__ == "__main__":
    unittest.main()
