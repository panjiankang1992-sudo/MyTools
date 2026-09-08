"""任务脚本上下文和子任务 API。"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
import uuid
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


@dataclass(frozen=True)
class TaskCheckpoint:
    """任务检查点视图。"""

    key: str
    version: int
    value: dict[str, Any]
    updated_at: str
    replayed: bool

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "TaskCheckpoint":
        """从 Scheduler 响应创建检查点。"""
        return cls(
            key=str(payload["key"]),
            version=int(payload["version"]),
            value=dict(payload.get("value", {})),
            updated_at=str(payload["updatedAt"]),
            replayed=bool(payload.get("replayed", False)),
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
        required_node_labels: dict[str, Any] | None = None,
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
                "requiredNodeLabels": required_node_labels or {},
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

    def get_task_results(self, task_id: str) -> dict[str, Any]:
        """查询当前任务或直接子任务的步骤执行结果。"""
        return self._request(
            "GET",
            f"/internal/v1/executions/{self.execution_id}/tasks/{task_id}/results",
            None,
        )

    def cancel_child(self, task_id: str) -> TaskInstance:
        """请求取消当前任务的直接子任务。"""
        payload = self._request(
            "POST",
            f"/internal/v1/executions/{self.execution_id}/tasks/{task_id}/cancel",
            {},
        )
        return TaskInstance.from_payload(payload)

    def put_checkpoint(
        self,
        key: str,
        value: dict[str, Any],
        expected_version: int,
        *,
        request_id: str | None = None,
    ) -> TaskCheckpoint:
        """按预期版本幂等写入当前任务检查点。"""
        stable_request_id = request_id or self._checkpoint_request_id(key, expected_version, value)
        payload = self._request(
            "PUT",
            f"/internal/v1/executions/{self.execution_id}/tasks/checkpoints/{key}",
            {
                "requestId": stable_request_id,
                "expectedVersion": expected_version,
                "value": value,
            },
        )
        return TaskCheckpoint.from_payload(payload)

    def get_checkpoint(self, key: str) -> TaskCheckpoint:
        """读取当前任务的指定检查点。"""
        payload = self._request(
            "GET",
            f"/internal/v1/executions/{self.execution_id}/tasks/checkpoints/{key}",
            None,
        )
        return TaskCheckpoint.from_payload(payload)

    def list_checkpoints(self) -> list[TaskCheckpoint]:
        """按键名列举当前任务的全部检查点。"""
        payload = self._request(
            "GET",
            f"/internal/v1/executions/{self.execution_id}/tasks/checkpoints",
            None,
        )
        return [TaskCheckpoint.from_payload(item) for item in payload]

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

    def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> Any:
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

    def _checkpoint_request_id(self, key: str, expected_version: int, value: dict[str, Any]) -> str:
        task_id = str(self.context["taskInstanceId"])
        canonical_value = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        identity = f"{task_id}:checkpoint:{key}:{expected_version}:{canonical_value}"
        return str(uuid.uuid5(uuid.NAMESPACE_URL, identity))
