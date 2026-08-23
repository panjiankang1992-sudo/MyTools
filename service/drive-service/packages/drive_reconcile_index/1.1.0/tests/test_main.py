import importlib.util
import io
import json
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("drive_reconcile_index_1_1", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

ACCOUNT_ID = "00000000-0000-4000-8000-000000000001"
PROVIDER_ID = "00000000-0000-4000-8000-000000000002"
OPERATION_ID = "00000000-0000-4000-8000-000000000003"


class Response:
    def __init__(self, payload):
        self.stream = io.BytesIO(json.dumps(payload).encode())

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.stream.close()

    def read(self):
        return self.stream.read()


def parameters():
    return {"migrationKey": "drive-provider-v1", "accountId": ACCOUNT_ID,
            "storageProviderId": PROVIDER_ID, "storageOperationId": OPERATION_ID}


class DriveReconcileIndexTest(unittest.TestCase):
    def test_reports_strict_digest_match(self):
        def opener(request, timeout):
            if request.full_url.endswith("/digest"):
                return Response({"itemCount": 2, "contentSha256": "a" * 64})
            return Response({"providerId": PROVIDER_ID, "operationType": "SCAN_ROOT",
                             "sourcePath": "", "status": "SUCCEEDED"})

        result = MODULE.execute(parameters(), "http://drive", "d", "http://storage", "s", opener)

        self.assertTrue(result["matched"])
        self.assertEqual([], result["mismatchReasons"])

    def test_reports_count_and_content_mismatch(self):
        calls = 0

        def opener(request, timeout):
            nonlocal calls
            calls += 1
            if request.full_url.endswith("/operations/" + OPERATION_ID):
                return Response({"providerId": PROVIDER_ID, "operationType": "SCAN_ROOT",
                                 "sourcePath": "", "status": "SUCCEEDED"})
            return Response({"itemCount": calls, "contentSha256": ("a" if calls == 1 else "b") * 64})

        result = MODULE.execute(parameters(), "http://drive", "d", "http://storage", "s", opener)

        self.assertFalse(result["matched"])
        self.assertEqual(["COUNT_MISMATCH", "CONTENT_MISMATCH"], result["mismatchReasons"])

    def test_rejects_scan_from_another_provider(self):
        def opener(request, timeout):
            if "drive" in request.full_url:
                return Response({"itemCount": 0, "contentSha256": "a" * 64})
            return Response({"providerId": ACCOUNT_ID, "operationType": "SCAN_ROOT",
                             "sourcePath": "", "status": "SUCCEEDED"})

        with self.assertRaisesRegex(RuntimeError, "root scan"):
            MODULE.execute(parameters(), "http://drive", "d", "http://storage", "s", opener)


if __name__ == "__main__":
    unittest.main()
