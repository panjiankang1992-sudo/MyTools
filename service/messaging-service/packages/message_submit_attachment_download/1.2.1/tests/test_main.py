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
    def test_submits_without_creating_blocking_reconciliation_task(self, _submit):
        """提交后交给持久化服务对账，不再创建占槽子任务。"""
        task = FakeTask()
        result = MODULE.execute(task, "http://messaging", "secret")
        self.assertEqual("SUBMITTED", result["status"])
        self.assertEqual([], task.created)
        self.assertNotIn("reconciliationTaskId", result)
        self.assertNotIn("secret", str(result))


if __name__ == "__main__":
    unittest.main()


class TerminalReplayTest(unittest.TestCase):
    """已提交且已结束的附件作业重放不能再被误判成提交失败。"""
    def test_completed_submission_is_idempotently_accepted(self):
        import io,json
        job = FakeTask.parameters['attachmentJobId']
        response = {'jobId':job,'downloadRequestId':'00000000-0000-4000-8000-000000000002','status':'SUCCEEDED'}
        with patch.object(MODULE.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(response).encode())):
            self.assertEqual('SUCCEEDED',MODULE.submit(job,'http://messaging','secret')['status'])
    def test_mismatched_job_is_rejected(self):
        import io,json
        response = {'jobId':'other','downloadRequestId':'00000000-0000-4000-8000-000000000002','status':'SUBMITTED'}
        with patch.object(MODULE.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(response).encode())):
            with self.assertRaises(RuntimeError):MODULE.submit(FakeTask.parameters['attachmentJobId'],'http://messaging','secret')
