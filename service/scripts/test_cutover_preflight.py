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


if __name__ == "__main__":
    unittest.main()
