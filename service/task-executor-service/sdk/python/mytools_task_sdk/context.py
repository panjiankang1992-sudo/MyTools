"""任务脚本上下文和子任务 API。"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class TaskInstance:
    """任务实例简化视图。"""

    id: str
    task_name: str
    status: str
    parent_task_instance_id: str | None

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "TaskInstance":
        """从 Scheduler 响应创建任务实例。"""
        return cls(
            id=str(payload["id"]),
            task_name=str(payload["taskName"]),
            status=str(payload["status"]),
            parent_task_instance_id=payload.get("parentTaskInstanceId"),
        )


class TaskContext:
    """当前脚本的任务上下文。"""

    TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}

    def __init__(self, context: dict[str, Any], api_url: str, execution_id: str, lease_token: str) -> None:
        self.context = context
        self.api_url = api_url.rstrip("/")
        self.execution_id = execution_id
        self.lease_token = lease_token

    @classmethod
    def load(cls) -> "TaskContext":
        """从 Executor 注入的文件和环境变量加载上下文。"""
        context_path = Path(os.environ["TASK_CONTEXT_FILE"])
        token_path = Path(os.environ["TASK_LEASE_TOKEN_FILE"])
        context = json.loads(context_path.read_text(encoding="utf-8"))
        return cls(
            context=context,
            api_url=os.environ["TASK_API_URL"],
            execution_id=os.environ["TASK_EXECUTION_ID"],
            lease_token=token_path.read_text(encoding="utf-8").strip(),
        )

    @property
    def parameters(self) -> dict[str, Any]:
        """返回任务参数。"""
        return dict(self.context.get("parameters", {}))

    def create_child(
        self,
        task_name: str,
        parameters: dict[str, Any],
        idempotency_key: str,
        *,
        business_type: str | None = None,
        business_id: str | None = None,
        priority: int = 50,
    ) -> TaskInstance:
        """幂等创建当前任务的直接子任务。"""
        payload = self._request(
            "POST",
            f"/internal/v1/executions/{self.execution_id}/tasks/children",
            {
                "leaseToken": self.lease_token,
                "taskName": task_name,
                "idempotencyKey": idempotency_key,
                "businessType": business_type,
                "businessId": business_id,
                "priority": priority,
                "parameters": parameters,
            },
        )
        return TaskInstance.from_payload(payload)

    def get_task(self, task_id: str) -> TaskInstance:
        """查询当前任务或直接子任务状态。"""
        payload = self._request(
            "GET",
            f"/internal/v1/executions/{self.execution_id}/tasks/{task_id}",
            None,
        )
        return TaskInstance.from_payload(payload)

    def cancel_child(self, task_id: str) -> TaskInstance:
        """请求取消当前任务的直接子任务。"""
        payload = self._request(
            "POST",
            f"/internal/v1/executions/{self.execution_id}/tasks/{task_id}/cancel",
            {},
        )
        return TaskInstance.from_payload(payload)

    def wait_child(self, task_id: str, timeout_seconds: float, poll_seconds: float = 1.0) -> TaskInstance:
        """等待直接子任务进入终态。"""
        deadline = time.monotonic() + timeout_seconds
        while True:
            task = self.get_task(task_id)
            if task.status in self.TERMINAL_STATUSES:
                return task
            if time.monotonic() >= deadline:
                raise TimeoutError(f"child task {task_id} did not finish before timeout")
            time.sleep(max(poll_seconds, 0.1))

    def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.api_url + path,
            data=data,
            method=method,
            headers={
                "Content-Type": "application/json",
                "X-Task-Lease-Token": self.lease_token,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exception:
            body = exception.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"task API failed with HTTP {exception.code}: {body[:512]}") from exception
