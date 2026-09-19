"""HTTP adapter for the Task Scheduler public API."""

from __future__ import annotations

import json
from urllib.request import Request, urlopen
from uuid import UUID


class TaskSchedulerHttpClient:
    """Create, query, and cancel scheduler task instances over HTTP."""

    def __init__(self, base_url: str, timeout_seconds: float = 10,
                 service_id: str = "", business_token: str = ""):
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._service_id = service_id
        self._business_token = business_token

    def create_task(self, *, task_name: str, idempotency_key: str,
                    business_id: str, parameters: dict) -> UUID:
        """Idempotently create one scheduler task instance."""
        payload = {
            "taskName": task_name,
            "idempotencyKey": idempotency_key,
            "businessType": "DOWNLOAD_REQUEST",
            "businessId": business_id,
            "parentTaskInstanceId": None,
            # 新请求优先于故障恢复期间形成的历史积压，空闲时仍会按创建时间清空旧队列。
            "priority": 60,
            "parameters": parameters,
        }
        result = self._request("POST", "/api/v1/task-instances", payload)
        return UUID(result["id"])

    def get_task(self, task_id: UUID) -> dict:
        """Return the current scheduler task representation."""
        return self._request("GET", f"/api/v1/task-instances/{task_id}")

    def cancel_task(self, task_id: UUID) -> dict:
        """Request cancellation of one scheduler task."""
        return self._request("POST", f"/api/v1/task-instances/{task_id}/cancel", {})

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self._service_id and self._business_token:
            # 调度器启用独立业务身份后，下载编排必须携带调用方身份和令牌。
            headers["X-Task-Service-Id"] = self._service_id
            headers["X-Task-Business-Token"] = self._business_token
        request = Request(f"{self._base_url}{path}", data=body, method=method, headers=headers)
        with urlopen(request, timeout=self._timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
