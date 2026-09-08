"""任务检查点 SDK 测试。"""

from __future__ import annotations

import json
import unittest
from unittest.mock import patch

from mytools_task_sdk.context import TaskContext


class _Response:
    def __init__(self, payload: object) -> None:
        self._payload = payload

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self._payload).encode("utf-8")


class TaskCheckpointSdkTest(unittest.TestCase):
    """验证检查点请求身份与响应映射。"""

    def setUp(self) -> None:
        self.context = TaskContext(
            {"taskInstanceId": "11111111-1111-4111-8111-111111111111"},
            "http://scheduler", "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
        )

    def test_should_generate_stable_request_id_for_semantically_equal_value(self) -> None:
        first = self.context._checkpoint_request_id("scan.cursor", 3, {"offset": 10, "done": False})
        second = self.context._checkpoint_request_id("scan.cursor", 3, {"done": False, "offset": 10})

        self.assertEqual(first, second)

    @patch("urllib.request.urlopen")
    def test_should_write_and_list_checkpoints(self, urlopen) -> None:
        urlopen.side_effect = [
            _Response({
                "key": "scan.cursor", "version": 1, "value": {"offset": 10},
                "updatedAt": "2026-09-02T00:00:00Z", "replayed": False,
            }),
            _Response([{
                "key": "scan.cursor", "version": 1, "value": {"offset": 10},
                "updatedAt": "2026-09-02T00:00:00Z", "replayed": False,
            }]),
        ]

        checkpoint = self.context.put_checkpoint("scan.cursor", {"offset": 10}, 0)
        checkpoints = self.context.list_checkpoints()

        self.assertEqual(1, checkpoint.version)
        self.assertEqual({"offset": 10}, checkpoints[0].value)
        request = urlopen.call_args_list[0].args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual(0, payload["expectedVersion"])
        self.assertEqual(self.context.lease_token, request.headers["X-task-lease-token"])


if __name__ == "__main__":
    unittest.main()
