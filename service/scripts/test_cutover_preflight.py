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
        self.assertEqual([], report["errors"])

    def test_rejects_shared_schema_and_enabled_sidecar(self):
        report = MODULE.inspect({"READER_DB_NAME": "mytools_task",
                                 "READER_SEARCH_SIDECAR_ENABLED": "true"})
        self.assertFalse(report["ready"])
        self.assertEqual(2, len(report["errors"]))


if __name__ == "__main__":
    unittest.main()
