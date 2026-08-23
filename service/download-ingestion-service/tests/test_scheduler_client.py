"""Contract tests for the Task Scheduler HTTP adapter."""

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from mytools_download_ingestion.scheduler_client import TaskSchedulerHttpClient


class FakeResponse:
    """Return one JSON scheduler response."""

    def __init__(self, payload):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self):
        """Return encoded JSON content."""
        return json.dumps(self._payload).encode("utf-8")


class TaskSchedulerHttpClientTest(unittest.TestCase):
    """Validate scheduler path and request document compatibility."""

    @patch("mytools_download_ingestion.scheduler_client.urlopen")
    def test_creates_download_task_with_business_identity(self, mocked_open):
        """The adapter must send the scheduler's public create contract."""
        task_id = uuid4()
        mocked_open.return_value = FakeResponse({"id": str(task_id)})
        client = TaskSchedulerHttpClient("http://scheduler")

        actual = client.create_task(
            task_name="download_http_asset",
            idempotency_key="download:key",
            business_id="request-1",
            parameters={"downloadRequestId": "request-1"},
        )

        self.assertEqual(task_id, actual)
        request = mocked_open.call_args.args[0]
        payload = json.loads(request.data)
        self.assertEqual("DOWNLOAD_REQUEST", payload["businessType"])
        self.assertEqual("request-1", payload["businessId"])
        self.assertEqual("download_http_asset", payload["taskName"])
