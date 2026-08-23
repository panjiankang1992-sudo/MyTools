"""Tests for bounded remote root scanning."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_scan_root", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    """Capture deterministic list and merge behavior."""

    def __init__(self):
        self.batches = []

    def list(self, _provider, path):
        """Return one directory then one child file."""
        if path == "":
            return [{"path": "books", "name": "books", "directory": True, "sizeBytes": 0}]
        return [{"path": "books/a.txt", "name": "a.txt", "directory": False, "sizeBytes": 3}]

    def merge(self, _operation, items):
        """Capture an item batch."""
        self.batches.extend(items)
        return {}

    def finish(self, _operation, status, _error=None):
        """Return a completed aggregate view."""
        return {"status": status, "itemCount": len(self.batches)}


class StorageScanRootTest(unittest.TestCase):
    """Validate breadth-first scanning and hard object limits."""

    def test_scans_directories_and_merges_items(self):
        """A directory is traversed and every object is merged."""
        client = FakeClient()
        result = MODULE.execute({"operationId": "op", "providerId": "provider",
                                 "rootPath": "", "maximumObjects": 10}, client)
        self.assertEqual(2, result["itemCount"])
        self.assertEqual(["books", "books/a.txt"], [item["path"] for item in client.batches])

    def test_rejects_scan_above_limit(self):
        """A scan cannot silently exceed its caller-approved bound."""
        with self.assertRaisesRegex(ValueError, "maximumObjects"):
            MODULE.execute({"operationId": "op", "providerId": "provider",
                            "rootPath": "", "maximumObjects": 1}, FakeClient())


if __name__ == "__main__":
    unittest.main()
