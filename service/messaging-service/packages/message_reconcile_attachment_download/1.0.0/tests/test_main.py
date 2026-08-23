import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_reconcile_attachment_download", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
JOB_ID = "00000000-0000-4000-8000-000000000001"


class MessageReconcileAttachmentDownloadTest(unittest.TestCase):

    @patch.object(MODULE, "read_status")
    def test_waits_for_terminal_status(self, read_status):
        read_status.side_effect = [
            {"id": JOB_ID, "status": "RUNNING", "downloadRequestId": "download"},
            {"id": JOB_ID, "status": "SUCCEEDED", "downloadRequestId": "download",
             "lastErrorCode": None}]
        result = MODULE.execute(JOB_ID, "http://messaging", "secret", 3, 1,
                                sleeper=lambda _: None)
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual(2, result["checks"])

    @patch.object(MODULE, "read_status", return_value={"id": JOB_ID, "status": "RUNNING"})
    def test_fails_after_bounded_checks(self, _read_status):
        with self.assertRaises(TimeoutError):
            MODULE.execute(JOB_ID, "http://messaging", "secret", 2, 1,
                           sleeper=lambda _: None)


if __name__ == "__main__":
    unittest.main()
