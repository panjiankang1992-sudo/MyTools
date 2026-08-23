import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_submit_attachment_download_v11", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeChild:
    id = "00000000-0000-4000-8000-000000000003"


class FakeTask:
    parameters = {"attachmentJobId": "00000000-0000-4000-8000-000000000001"}

    def __init__(self):
        self.created = []

    def create_child(self, name, parameters, key, **metadata):
        self.created.append((name, parameters, key, metadata))
        return FakeChild()


class MessageSubmitAttachmentDownloadV11Test(unittest.TestCase):

    @patch.object(MODULE, "submit", return_value={
        "jobId": "00000000-0000-4000-8000-000000000001",
        "downloadRequestId": "00000000-0000-4000-8000-000000000002",
        "status": "SUBMITTED"})
    def test_creates_idempotent_reconciliation_child(self, _submit):
        task = FakeTask()
        result = MODULE.execute(task, "http://messaging", "secret")

        self.assertEqual("00000000-0000-4000-8000-000000000003",
                         result["reconciliationTaskId"])
        self.assertEqual("message_reconcile_attachment_download", task.created[0][0])
        self.assertEqual({"attachmentJobId": task.parameters["attachmentJobId"]},
                         task.created[0][1])
        self.assertNotIn("secret", str(task.created))


if __name__ == "__main__":
    unittest.main()
