"""Tests for download result callback construction."""

import importlib.util
import json
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_record_result", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DownloadRecordResultTest(unittest.TestCase):
    """Validate result propagation without physical path disclosure."""

    def test_combines_step_outputs(self):
        """The callback carries verified identity and a logical storage URI."""
        context = {"stepOutputs": {"download_asset": {"itemId": "i1", "fileName": "a.bin",
                    "contentSha256": "a" * 64, "sizeBytes": 7, "relativePath": "r/a.bin"},
                    "register_asset": {"assetId": "asset-1"}}}
        payload = MODULE.build_payload(context)
        self.assertEqual("asset-1", payload["assetId"])
        self.assertEqual("download://executor/r/a.bin", payload["storageUri"])
        self.assertNotIn("sourcePath", json.dumps(payload))

    def test_preserves_storage_gateway_uri(self):
        """Managed imports retain their gateway URI instead of fabricating a local URI."""
        context = {"stepOutputs": {"download_asset": {"itemId": "i2", "fileName": "b.bin",
                    "contentSha256": "b" * 64, "sizeBytes": 8,
                    "storageUri": "storage://downloads/r/b.bin"},
                    "register_asset": {"assetId": "asset-2"}}}
        self.assertEqual("storage://downloads/r/b.bin", MODULE.build_payload(context)["storageUri"])


if __name__ == "__main__":
    unittest.main()
